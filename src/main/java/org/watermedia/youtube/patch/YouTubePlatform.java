package org.watermedia.youtube.patch;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSlave;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * YouTube platform implementation using NewPipeExtractor.
 * Extracts direct video/audio stream URLs from YouTube videos.
 *
 * <p>This platform supports:
 * <ul>
 *     <li>Standard YouTube video URLs (youtube.com/watch?v=...)</li>
 *     <li>Short YouTube URLs (youtu.be/...)</li>
 *     <li>YouTube Shorts URLs (youtube.com/shorts/...)</li>
 *     <li>Embedded YouTube URLs (youtube.com/embed/...)</li>
 * </ul>
 *
 * <p>High quality streams (FHD, 4K, 8K) on YouTube are video-only,
 * so this platform attaches an audio slave to provide the audio track.
 */
public class YouTubePlatform implements IPlatform {
    private static final Marker IT = MarkerManager.getMarker(YouTubePlatform.class.getSimpleName());
    private static final Pattern YOUTUBE_VIDEO_ID = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)");
    private static final Pattern RESOLUTION_PARSER = Pattern.compile("(\\d+)p");
    private static final Duration FALLBACK_TTL = Duration.ofHours(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String PLAYLIST_BASE_URL = "https://www.youtube.com/playlist?list=";
    private static final String EXPIRE_PARAM_PREFIX = "expire=";


    @Override
    public String name() {
        return "YouTube";
    }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.endsWith("youtube.com") || host.endsWith("youtu.be"));
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // Extract "list" query param inline
        String playlistId = null;
        final String query = uri.getRawQuery();
        if (query != null) {
            for (final String param : query.split("&")) {
                if (param.startsWith("list=")) {
                    playlistId = param.substring(5);
                    break;
                }
            }
        }

        final Matcher matcher = YOUTUBE_VIDEO_ID.matcher(uri.toString());
        final boolean hasVideoId = matcher.find();

        if (playlistId != null) {
            // Video link with a playlist param (e.g. /watch?v=xxx&list=RDxxx)
            // → fall back to the single video instead of rejecting
            if (hasVideoId) {
                try {
                    return this.playlist(playlistId);
                } catch (final IllegalArgumentException e) {
                    LOGGER.debug(IT, "Playlist from video link is dynamic, resolving single video: {}", e.getMessage());
                    final Result r = this.stream(matcher.group(1));
                    return new PlatformData(r.expires(), r.source());
                }
            }

            // Pure playlist link (e.g. /playlist?list=RDxxx)
            // → throw if dynamic/mix
            return this.playlist(playlistId);
        }

        if (!hasVideoId) {
            throw new IllegalArgumentException("Invalid YouTube URL: no video ID found in " + uri);
        }

        final Result r = this.stream(matcher.group(1));
        return new PlatformData(r.expires(), r.source());
    }

    /**
     * Extracts streams for a single video by its ID.
     */
    private Result stream(final String videoId) throws Exception {
        final var youtube = ServiceList.YouTube;
        final var extractor = youtube.getStreamExtractor(
                youtube.getStreamLHFactory().getUrl(videoId)
        );
        extractor.fetchPage();

        final StreamType streamType = extractor.getStreamType();

        // Audio-only content (music uploads, podcasts)
        if (streamType == StreamType.AUDIO_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            return this.audio(extractor);
        }

        // Video content (regular, live, post-live)
        return this.video(extractor);
    }

    /**
     * Builds a VIDEO source with every available resolution variant plus an audio slave.
     *
     * <p>Strategy:
     * <ol>
     *     <li>Video-only streams are added first (preferred, best quality per resolution)</li>
     *     <li>Muxed streams fill resolution tiers not covered by video-only</li>
     *     <li>An audio slave provides the audio track, essential for the video-only
     *         FHD/4K/8K streams that have no embedded audio</li>
     * </ol>
     */
    private Result video(final StreamExtractor extractor) throws Exception {
        final List<VideoStream> videoOnlyStreams = extractor.getVideoOnlyStreams();
        final List<VideoStream> muxedStreams = extractor.getVideoStreams();
        final List<AudioStream> audioStreams = extractor.getAudioStreams();

        final List<DataQuality> variants = new ArrayList<>();
        final Set<Integer> seenHeights = new HashSet<>();

        // Video-only first (higher priority: best quality, we supply audio via a slave)
        if (videoOnlyStreams != null) {
            for (final VideoStream s : videoOnlyStreams) {
                addVariant(variants, seenHeights, s);
            }
        }

        // Muxed streams as fallback (only fills resolution tiers not already covered)
        if (muxedStreams != null) {
            for (final VideoStream s : muxedStreams) {
                addVariant(variants, seenHeights, s);
            }
        }

        // No video streams at all — fall back to audio-only
        if (variants.isEmpty()) {
            return this.audio(extractor);
        }

        // Audio slave: provides the audio track for video-only streams (FHD, 4K, 8K)
        final List<DataSlave> audioSlaves = audioSlaves(audioStreams);

        final var source = new DataSource(
                MediaType.VIDEO,
                thumbnail(extractor),
                metadata(extractor),
                RequestHeaders.defaults(URI.create(extractor.getUrl())),
                variants.toArray(DataQuality[]::new),
                audioSlaves,
                null);

        final Instant exp = expiration(variants.get(0).uri());
        return new Result(exp, source);
    }

    /**
     * Builds an AUDIO source from the best available audio stream (music, podcasts, audio live).
     */
    private Result audio(final StreamExtractor extractor) throws Exception {
        final List<AudioStream> audioStreams = extractor.getAudioStreams();
        if (audioStreams == null || audioStreams.isEmpty()) {
            throw new IllegalStateException("No audio streams available for " + extractor.getUrl());
        }

        final URI bestAudio = URI.create(bestAudioStream(audioStreams).getContent());

        final var source = new DataSource(
                MediaType.AUDIO,
                thumbnail(extractor),
                metadata(extractor),
                RequestHeaders.defaults(URI.create(extractor.getUrl())),
                new DataQuality[] { new DataQuality(bestAudio, 0, 0) },
                null,
                null);

        return new Result(expiration(bestAudio), source);
    }

    /**
     * Builds sources for all videos in a YouTube playlist.
     * Rejects dynamic/auto-generated playlists (mixes) since their content varies per user.
     *
     * @param playlistId the YouTube playlist ID (e.g. PLxxx)
     * @throws IllegalArgumentException if the playlist is a mix or auto-generated
     */
    private PlatformData playlist(final String playlistId) throws Exception {
        final var youtube = ServiceList.YouTube;
        final var playlistExtractor = youtube.getPlaylistExtractor(PLAYLIST_BASE_URL + playlistId);
        playlistExtractor.fetchPage();

        // Reject mix/auto-generated playlists (dynamic content varies per user)
        final var playlistType = playlistExtractor.getPlaylistType();
        if (playlistType != PlaylistInfo.PlaylistType.NORMAL) {
            throw new IllegalArgumentException(
                    "Dynamic/auto-generated playlists are not supported: " + playlistType);
        }

        // Collect all stream items across all pages
        var page = playlistExtractor.getInitialPage();
        final var allItems = new ArrayList<>(page.getItems());
        while (page.hasNextPage()) {
            page = playlistExtractor.getPage(page.getNextPage());
            allItems.addAll(page.getItems());
        }

        if (allItems.isEmpty()) {
            throw new IllegalStateException("Playlist is empty: " + playlistId);
        }

        // Extract each video and collect sources
        final var sources = new ArrayList<DataSource>();
        Instant earliestExpiration = null;

        for (final var item : allItems) {
            try {
                final var streamExtractor = youtube.getStreamExtractor(item.getUrl());
                streamExtractor.fetchPage();

                final StreamType streamType = streamExtractor.getStreamType();

                final Result itemResult;
                if (streamType == StreamType.AUDIO_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
                    itemResult = this.audio(streamExtractor);
                } else {
                    itemResult = this.video(streamExtractor);
                }

                sources.add(itemResult.source());
                if (earliestExpiration == null
                        || (itemResult.expires() != null && itemResult.expires().isBefore(earliestExpiration))) {
                    earliestExpiration = itemResult.expires();
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "Skipping playlist item '{}': {}", item.getName(), e.getMessage());
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("No streams could be extracted from playlist: " + playlistId);
        }

        return new PlatformData(earliestExpiration, sources.toArray(DataSource[]::new));
    }

    /**
     * Internal holder pairing a single resolved {@link DataSource} with its expiration,
     * so the playlist path can aggregate multiple entries and compute the earliest expiration.
     */
    private record Result(Instant expires, DataSource source) {}

    // =========================================================================
    // EXPIRATION EXTRACTION
    // =========================================================================

    /**
     * Extracts the expiration {@link Instant} from the {@code expire} query parameter
     * present in YouTube's {@code /videoplayback} stream URLs.
     *
     * @param streamUri a stream URI containing an {@code expire=<epoch_seconds>} parameter
     * @return the expiration instant, or a fallback of now + 5 hours if parsing fails
     */
    private static Instant expiration(final URI streamUri) {
        final String query = streamUri.getRawQuery();
        if (query != null) {
            for (final String param : query.split("&")) {
                if (param.startsWith(EXPIRE_PARAM_PREFIX)) {
                    try {
                        return Instant.ofEpochSecond(Long.parseLong(param.substring(EXPIRE_PARAM_PREFIX.length())));
                    } catch (final NumberFormatException ignored) {}
                }
            }
        }
        LOGGER.warn(IT, "Could not extract 'expire' parameter from stream URL, using fallback TTL");
        return Instant.now().plus(FALLBACK_TTL);
    }

    // =========================================================================
    // QUALITY MAPPING
    // =========================================================================

    /**
     * Adds a video stream as a {@link DataQuality} variant, skipping resolutions already
     * present. Called video-only first then muxed, so video-only renditions win each tier.
     */
    private static void addVariant(final List<DataQuality> variants, final Set<Integer> seenHeights, final VideoStream stream) {
        final int height = resolutionHeight(stream);
        if (height > 0 && !seenHeights.add(height)) {
            return; // a stream for this resolution was already added
        }
        // YouTube is landscape; supply a synthetic 16:9 width so MediaQuality.of picks the height
        final int width = height > 0 ? height * 16 / 9 : 0;
        variants.add(new DataQuality(URI.create(stream.getContent()), width, height));
    }

    /**
     * Resolves the pixel height of a NewPipe VideoStream.
     * Uses the resolution string (e.g. "1080p60") with a fallback to the height field.
     */
    private static int resolutionHeight(final VideoStream stream) {
        final String resolution = stream.getResolution();
        if (resolution != null && !resolution.isEmpty()) {
            final Matcher m = RESOLUTION_PARSER.matcher(resolution);
            if (m.find()) {
                try {
                    return Integer.parseInt(m.group(1));
                } catch (final NumberFormatException ignored) {}
            }
        }
        return Math.max(stream.getHeight(), 0);
    }

    /**
     * Wraps the highest-bitrate audio stream as a single audio slave, or {@code null} when
     * none are available. YouTube exposes audio as alternative tracks rather than quality
     * tiers, so a single best-bitrate slave covers the video-only renditions.
     */
    private static List<DataSlave> audioSlaves(final List<AudioStream> streams) {
        if (streams == null || streams.isEmpty()) return null;
        final URI uri = URI.create(bestAudioStream(streams).getContent());
        return List.of(new DataSlave(null, null, uri));
    }

    /**
     * Picks the audio stream with the highest average bitrate, falling back to the first
     * stream when no bitrate information is reported.
     */
    private static AudioStream bestAudioStream(final List<AudioStream> streams) {
        return streams.stream()
                .filter(s -> s.getAverageBitrate() > 0)
                .max(Comparator.comparingInt(AudioStream::getAverageBitrate))
                .orElse(streams.get(0));
    }

    // =========================================================================
    // METADATA
    // =========================================================================

    /**
     * Extracts the highest-resolution thumbnail URI from the StreamExtractor, or {@code null}.
     */
    private static URI thumbnail(final StreamExtractor extractor) {
        try {
            final var thumbs = extractor.getThumbnails();
            if (thumbs != null && !thumbs.isEmpty()) {
                // Last thumbnail is typically highest resolution
                return URI.create(thumbs.get(thumbs.size() - 1).getUrl());
            }
        } catch (final Exception ignored) {}
        return null;
    }

    /**
     * Extracts video metadata from the StreamExtractor.
     * Each field is extracted independently to avoid one failure breaking all metadata.
     */
    private static Metadata metadata(final StreamExtractor extractor) {
        String title = null;
        String description = null;
        Instant publishedAt = null;
        long durationMs = 0;
        String author = null;

        try { title = extractor.getName(); } catch (final Exception ignored) {}

        try {
            final var desc = extractor.getDescription();
            description = desc.getContent();
        } catch (final Exception ignored) {}

        try { author = extractor.getUploaderName(); } catch (final Exception ignored) {}

        try {
            final long seconds = extractor.getLength();
            if (seconds > 0) durationMs = seconds * 1000L;
        } catch (final Exception ignored) {}

        try {
            final var uploadDate = extractor.getUploadDate();
            if (uploadDate != null) {
                publishedAt = uploadDate.offsetDateTime().toInstant();
            }
        } catch (final Exception ignored) {}

        return new Metadata(title, description, publishedAt, durationMs, author);
    }

    // =========================================================================
    // NEWPIPE HTTP DOWNLOADER
    // =========================================================================

    /**
     * Custom Downloader implementation for NewPipeExtractor using Java's HttpClient.
     * Required by NewPipe to make HTTP requests to YouTube.
     */
    public static class WaterMediaDownloader extends Downloader {
        private final HttpClient httpClient;

        public WaterMediaDownloader() {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(REQUEST_TIMEOUT)
                    .build();
        }

        @Override
        public Response execute(final Request request) throws IOException {
            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(request.url()))
                    .timeout(REQUEST_TIMEOUT);

            // Add headers
            final var headers = request.headers();
            if (headers != null) {
                for (final var entry : headers.entrySet()) {
                    for (final String value : entry.getValue()) {
                        builder.header(entry.getKey(), value);
                    }
                }
            }

            // Set method and body
            final byte[] data = request.dataToSend();
            if (data != null && data.length > 0) {
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(data));
            } else {
                builder.GET();
            }

            try {
                final HttpResponse<String> response = this.httpClient.send(
                        builder.build(),
                        HttpResponse.BodyHandlers.ofString()
                );

                return new Response(
                        response.statusCode(),
                        response.body(),
                        response.headers().map(),
                        response.body(),
                        response.uri().toString()
                );
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Request interrupted", e);
            }
        }
    }
}
