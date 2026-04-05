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
import org.watermedia.api.media.MRL;
import org.watermedia.api.media.platform.IPlatform;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
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
 * so this platform attaches audio slaves to provide the audio track
 * at multiple bitrate tiers matched to the video quality level.
 */
public class YouTubePlatform implements IPlatform {
    private static final Marker IT = MarkerManager.getMarker(YouTubePlatform.class.getSimpleName());
    private static final Pattern YOUTUBE_VIDEO_ID = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)");
    private static final Pattern RESOLUTION_PARSER = Pattern.compile("(\\d+)p");
    private static final Duration FALLBACK_TTL = Duration.ofHours(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final String PLAYLIST_BASE_URL = "https://www.youtube.com/playlist?list=";
    private static final String EXPIRE_PARAM_PREFIX = "expire=";
    private static final Map<String, Integer> RESOLUTION_HEIGHT = Map.ofEntries(
            Map.entry("144p", 144),
            Map.entry("240p", 240),
            Map.entry("360p", 360),
            Map.entry("480p", 480),
            Map.entry("720p", 720),
            Map.entry("720p60", 720),
            Map.entry("1080p", 1080),
            Map.entry("1080p60", 1080),
            Map.entry("1440p", 1440),
            Map.entry("1440p60", 1440),
            Map.entry("2160p", 2160),
            Map.entry("2160p60", 2160),
            Map.entry("4320p", 4320),
            Map.entry("4320p60", 4320)
    );


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
    public Result getSources(final URI uri) throws Exception {
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
                    return this.stream(matcher.group(1));
                }
            }

            // Pure playlist link (e.g. /playlist?list=RDxxx)
            // → throw if dynamic/mix
            return this.playlist(playlistId);
        }

        if (!hasVideoId) {
            throw new IllegalArgumentException("Invalid YouTube URL: no video ID found in " + uri);
        }

        return this.stream(matcher.group(1));
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

        final MRL.Metadata meta = metadata(extractor);
        final StreamType streamType = extractor.getStreamType();

        // Audio-only content (music uploads, podcasts)
        if (streamType == StreamType.AUDIO_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
            return this.audio(extractor, meta);
        }

        // Video content (regular, live, post-live)
        return this.video(extractor, meta);
    }

    /**
     * Builds a VIDEO source with multiple quality tiers and audio slaves.
     *
     * <p>Strategy:
     * <ol>
     *     <li>Video-only streams are added first (preferred, best quality per resolution)</li>
     *     <li>Muxed streams fill remaining quality tiers not covered by video-only</li>
     *     <li>Audio slaves provide the audio track at multiple bitrate tiers,
     *         essential for video-only FHD/4K/8K streams that have no embedded audio</li>
     * </ol>
     */
    private Result video(final StreamExtractor extractor, final MRL.Metadata metadata) throws Exception {
        final List<VideoStream> videoOnlyStreams = extractor.getVideoOnlyStreams();
        final List<VideoStream> muxedStreams = extractor.getVideoStreams();
        final List<AudioStream> audioStreams = extractor.getAudioStreams();

        final EnumMap<MRL.Quality, URI> videoQualities = new EnumMap<>(MRL.Quality.class);

        // Video-only first (higher priority: best quality, we supply audio via slaves)
        if (videoOnlyStreams != null) {
            for (final VideoStream s: videoOnlyStreams) {
                videoQualities.putIfAbsent(videoQuality(s), URI.create(s.getContent()));
            }
        }

        // Muxed streams as fallback (only fills quality tiers not already covered)
        if (muxedStreams != null) {
            for (final VideoStream s: muxedStreams) {
                videoQualities.putIfAbsent(videoQuality(s), URI.create(s.getContent()));
            }
        }

        // No video streams at all — fall back to audio-only
        if (videoQualities.isEmpty()) {
            return this.audio(extractor, metadata);
        }

        // Assemble the source
        final var builder = MRL.sourceBuilder(MRL.MediaType.VIDEO).metadata(metadata);
        for (final var entry : videoQualities.entrySet()) {
            builder.quality(entry.getKey(), entry.getValue());
        }

        // Audio slave: provides the audio track for video-only streams (FHD, 4K, 8K)
        // and an alternative audio track for muxed streams
        if (audioStreams != null && !audioStreams.isEmpty()) {
            final var aq = this.audioQualities(audioStreams);
            if (!aq.isEmpty()) {
                builder.slave(new MRL.Slave(MRL.SlaveType.AUDIO, null, null, aq));
            }
        }

        final Instant exp = expiration(videoQualities.values().iterator().next());
        return new Result(exp, builder.build());
    }

    /**
     * Builds an AUDIO source with multiple bitrate tiers (music, podcasts, audio live).
     */
    private Result audio(final StreamExtractor extractor, final MRL.Metadata metadata) throws Exception {
        final List<AudioStream> audioStreams = extractor.getAudioStreams();
        if (audioStreams == null || audioStreams.isEmpty()) {
            throw new IllegalStateException("No audio streams available for " + extractor.getUrl());
        }

        final var builder = MRL.sourceBuilder(MRL.MediaType.AUDIO).metadata(metadata);
        final var aq = this.audioQualities(audioStreams);
        for (final var entry : aq.entrySet()) {
            builder.quality(entry.getKey(), entry.getValue());
        }

        final Instant exp = expiration(aq.values().iterator().next());
        return new Result(exp, builder.build());
    }

    /**
     * Builds sources for all videos in a YouTube playlist.
     * Rejects dynamic/auto-generated playlists (mixes) since their content varies per user.
     *
     * @param playlistId the YouTube playlist ID (e.g. PLxxx)
     * @throws IllegalArgumentException if the playlist is a mix or auto-generated
     */
    private Result playlist(final String playlistId) throws Exception {
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
        final var sources = new ArrayList<MRL.Source>();
        Instant earliestExpiration = null;

        for (final var item : allItems) {
            try {
                final var streamExtractor = youtube.getStreamExtractor(item.getUrl());
                streamExtractor.fetchPage();

                final MRL.Metadata meta = metadata(streamExtractor);
                final StreamType streamType = streamExtractor.getStreamType();

                final Result itemResult;
                if (streamType == StreamType.AUDIO_STREAM || streamType == StreamType.AUDIO_LIVE_STREAM) {
                    itemResult = this.audio(streamExtractor, meta);
                } else {
                    itemResult = this.video(streamExtractor, meta);
                }

                Collections.addAll(sources, itemResult.sources());
                if (earliestExpiration == null || itemResult.expires().isBefore(earliestExpiration)) {
                    earliestExpiration = itemResult.expires();
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "Skipping playlist item '{}': {}", item.getName(), e.getMessage());
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("No streams could be extracted from playlist: " + playlistId);
        }

        return new Result(earliestExpiration, sources.toArray(MRL.Source[]::new));
    }

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
     * Maps a NewPipe VideoStream to the corresponding MRL.Quality level.
     * Uses resolution string (e.g. "1080p60") with fallback to pixel height.
     */
    private static MRL.Quality videoQuality(final VideoStream stream) {
        int height = 0;

        // Resolution string is more reliable on YouTube
        final String resolution = stream.getResolution();
        if (!resolution.isEmpty()) {
            final Integer mapped = RESOLUTION_HEIGHT.get(resolution);
            if (mapped != null) {
                height = mapped;
            } else {
                final Matcher m = RESOLUTION_PARSER.matcher(resolution);
                if (m.find()) {
                    try { height = Integer.parseInt(m.group(1)); } catch (final NumberFormatException ignored) {}
                }
            }
        }

        // Fallback to the height field
        if (height <= 0) {
            height = stream.getHeight();
        }

        if (height <= 0) return MRL.Quality.UNKNOWN;

        // Quality.of(width, height) uses Math.min(w, h) internally.
        // YouTube is landscape, so height is always the smaller dimension.
        // We provide a synthetic width so Math.min correctly returns the height.
        return MRL.Quality.of(height * 16 / 9, height);
    }

    /**
     * Builds audio quality tiers from available audio streams.
     * Maps bitrate ranges to MRL.Quality levels for the Slave's quality EnumMap,
     * so the player can pick audio quality matched to the selected video quality.
     *
     * <ul>
     *     <li>LOWEST  — lowest bitrate (for 144p–360p playback)</li>
     *     <li>MEDIUM  — mid bitrate    (for 480p–720p playback)</li>
     *     <li>HIGHEST — best bitrate   (for FHD, 4K, 8K playback)</li>
     * </ul>
     */
    private EnumMap<MRL.Quality, URI> audioQualities(final List<AudioStream> streams) {
        final var qualities = new EnumMap<MRL.Quality, URI>(MRL.Quality.class);

        final List<AudioStream> sorted = streams.stream()
                .filter(s -> s.getAverageBitrate() > 0)
                .sorted(Comparator.comparingInt(AudioStream::getAverageBitrate))
                .toList();

        if (sorted.isEmpty()) {
            // No bitrate info available — use first valid stream as single quality
            for (final AudioStream s: streams) {
                qualities.put(MRL.Quality.UNKNOWN, URI.create(s.getContent()));
                break;
            }
            return qualities;
        }

        // Lowest bitrate for low quality playback
        qualities.put(MRL.Quality.LOWEST, URI.create(sorted.get(0).getContent()));

        // Highest bitrate for FHD/4K/8K playback
        qualities.put(MRL.Quality.HIGHEST, URI.create(sorted.get(sorted.size() - 1).getContent()));

        // Mid-tier for medium quality playback
        if (sorted.size() >= 3) {
            qualities.put(MRL.Quality.MEDIUM, URI.create(sorted.get(sorted.size() / 2).getContent()));
        }

        return qualities;
    }

    // =========================================================================
    // METADATA
    // =========================================================================

    /**
     * Extracts video metadata from the StreamExtractor.
     * Each field is extracted independently to avoid one failure breaking all metadata.
     */
    private static MRL.Metadata metadata(final StreamExtractor extractor) {
        String title = null;
        String description = null;
        URI thumbnail = null;
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
            final var thumbs = extractor.getThumbnails();
            if (!thumbs.isEmpty()) {
                // Last thumbnail is typically highest resolution
                thumbnail = URI.create(thumbs.get(thumbs.size() - 1).getUrl());
            }
        } catch (final Exception ignored) {}

        try {
            final var uploadDate = extractor.getUploadDate();
            if (uploadDate != null) {
                publishedAt = uploadDate.offsetDateTime().toInstant();
            }
        } catch (final Exception ignored) {}

        return new MRL.Metadata(title, description, thumbnail, publishedAt, durationMs, author);
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
