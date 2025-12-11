package org.watermedia.youtube.patch;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.localization.Localization;
import org.schabi.newpipe.extractor.services.youtube.YoutubeService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.schabi.newpipe.extractor.stream.StreamType;
import org.schabi.newpipe.extractor.stream.VideoStream;
import org.watermedia.api.network.patchs.AbstractPatch;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YouTube URL patch implementation using NewPipeExtractor.
 * Extracts direct video/audio stream URLs from YouTube videos.
 *
 * <p>This patch supports:
 * <ul>
 *     <li>Standard YouTube video URLs (youtube.com/watch?v=...)</li>
 *     <li>Short YouTube URLs (youtu.be/...)</li>
 *     <li>YouTube Shorts URLs (youtube.com/shorts/...)</li>
 *     <li>Embedded YouTube URLs (youtube.com/embed/...)</li>
 * </ul>
 *
 * <p>The extractor pattern used follows NewPipe's architecture:
 * <ol>
 *     <li>Initialize NewPipe with a custom Downloader implementation</li>
 *     <li>Get the YouTube service from ServiceList</li>
 *     <li>Create a StreamExtractor for the given URL</li>
 *     <li>Fetch the page data</li>
 *     <li>Extract video/audio streams based on quality preference</li>
 * </ol>
 */
public class YouTubePatch extends AbstractPatch {

    private static final Pattern YOUTUBE_PATTERN = Pattern.compile(
            "(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)"
    );

    private static volatile boolean initialized = false;
    private static final Object INIT_LOCK = new Object();

    /**
     * Resolution values mapped to approximate pixel heights for quality comparison
     */
    private static final Map<String, Integer> RESOLUTION_MAP = Map.ofEntries(
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

    /**
     * Quality to target resolution mapping
     */
    private static final Map<Quality, Integer> QUALITY_TARGET = Map.of(
            Quality.LOWEST, 144,
            Quality.LOW, 360,
            Quality.MIDDLE, 720,
            Quality.HIGH, 1080,
            Quality.HIGHEST, Integer.MAX_VALUE
    );

    public YouTubePatch() {
        ensureInitialized();
    }

    /**
     * Ensures NewPipe is initialized with our custom Downloader.
     * Thread-safe initialization using double-checked locking.
     */
    private static void ensureInitialized() {
        if (!initialized) {
            synchronized (INIT_LOCK) {
                if (!initialized) {
                    NewPipe.init(new WaterMediaDownloader(), new Localization("en", "US"));
                    initialized = true;
                }
            }
        }
    }

    @Override
    public String platform() {
        return "YouTube";
    }

    @Override
    public boolean isValid(final URI uri) {
        return uri.getHost() != null && YOUTUBE_PATTERN.matcher(uri.toString()).find();
    }

    @Override
    public Result patch(final URI uri, Quality prefQuality) throws FixingURLException {
        prefQuality = prefQuality != null ? prefQuality : Quality.HIGHEST;

        if (!this.isValid(uri)) {
            throw new FixingURLException(uri, new IllegalArgumentException("Invalid YouTube URL"));
        }

        try {
            final Matcher matcher = YOUTUBE_PATTERN.matcher(uri.toString());
            if (!matcher.find()) throw new FixingURLException(uri, new IllegalArgumentException("Invalid YouTube URL, not video ID found"));
            final String videoId = matcher.group(1);

            final YoutubeService youtube = ServiceList.YouTube;
            final String url = youtube.getStreamLHFactory().getUrl(videoId);
            final StreamExtractor extractor = youtube.getStreamExtractor(url);

            // Fetch the page - this is where NewPipe does the actual extraction
            extractor.fetchPage();

            final StreamType streamType = extractor.getStreamType();
            final boolean isLiveStream = streamType == StreamType.LIVE_STREAM
                    || streamType == StreamType.AUDIO_LIVE_STREAM;
            final boolean isVideoContent = streamType != StreamType.AUDIO_STREAM
                    && streamType != StreamType.AUDIO_LIVE_STREAM;

            URI videoUri = null;
            URI audioUri = null;

            if (isVideoContent) {
                // Try to get video streams with audio first (muxed streams)
                final List<VideoStream> videoStreams = extractor.getVideoStreams();
                VideoStream selectedVideo = this.selectVideoStream(videoStreams, prefQuality);

                if (selectedVideo != null && selectedVideo.getContent() != null) {
                    videoUri = URI.create(selectedVideo.getContent());
                } else {
                    // Fallback to video-only streams
                    final List<VideoStream> videoOnlyStreams = extractor.getVideoOnlyStreams();
                    selectedVideo = this.selectVideoStream(videoOnlyStreams, prefQuality);

                    if (selectedVideo != null && selectedVideo.getContent() != null) {
                        videoUri = URI.create(selectedVideo.getContent());
                    }
                }
            }

            // Get audio stream (for video-only or audio-only content)
            final List<AudioStream> audioStreams = extractor.getAudioStreams();
            final AudioStream selectedAudio = this.selectAudioStream(audioStreams, prefQuality);

            if (selectedAudio != null && selectedAudio.getContent() != null) {
                audioUri = URI.create(selectedAudio.getContent());
            }

            // Determine the primary URI to return
            final URI primaryUri = videoUri != null ? videoUri : audioUri;

            if (primaryUri == null) {
                throw new FixingURLException(uri, new ExtractionException("No streams available"));
            }

            final Result result = new Result(primaryUri, isVideoContent, isLiveStream, fallbackUri -> {
                // Fallback: try to get any available stream
                try {
                    final List<VideoStream> fallbackStreams = extractor.getVideoStreams();
                    if (!fallbackStreams.isEmpty()) {
                        final String content = fallbackStreams.get(0).getContent();
                        if (content != null) {
                            return new Result(URI.create(content), true, isLiveStream);
                        }
                    }
                } catch (final Exception e) {
                    // Ignore fallback errors
                }
                return null;
            });

            // Set separate audio track if we have video-only stream
            if (videoUri != null && audioUri != null) {
                result.setAudioTrack(audioUri);
            }

            return result;

        } catch (final IOException | ExtractionException e) {
            throw new FixingURLException(uri, e);
        }
    }

    /**
     * Selects the best video stream based on quality preference.
     *
     * @param streams Available video streams
     * @param quality Preferred quality level
     * @return Selected VideoStream or null if none available
     */
    private VideoStream selectVideoStream(final List<VideoStream> streams, final Quality quality) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }

        final int targetResolution = QUALITY_TARGET.getOrDefault(quality, 720);

        if (quality == Quality.HIGHEST) {
            // Get the highest resolution available
            return streams.stream()
                    .filter(s -> s.getContent() != null)
                    .max(Comparator.comparingInt(this::getResolutionValue))
                    .orElse(streams.get(0));
        } else if (quality == Quality.LOWEST) {
            // Get the lowest resolution available
            return streams.stream()
                    .filter(s -> s.getContent() != null)
                    .min(Comparator.comparingInt(this::getResolutionValue))
                    .orElse(streams.get(0));
        } else {
            // Find the closest resolution to target
            return streams.stream()
                    .filter(s -> s.getContent() != null)
                    .min(Comparator.comparingInt(s ->
                            Math.abs(this.getResolutionValue(s) - targetResolution)))
                    .orElse(streams.get(0));
        }
    }

    /**
     * Selects the best audio stream based on quality preference.
     *
     * @param streams Available audio streams
     * @param quality Preferred quality level
     * @return Selected AudioStream or null if none available
     */
    private AudioStream selectAudioStream(final List<AudioStream> streams, final Quality quality) {
        if (streams == null || streams.isEmpty()) {
            return null;
        }

        if (quality == Quality.HIGHEST || quality == Quality.HIGH) {
            // Get highest bitrate
            return streams.stream()
                    .filter(s -> s.getContent() != null)
                    .max(Comparator.comparingInt(AudioStream::getAverageBitrate))
                    .orElse(streams.get(0));
        } else if (quality == Quality.LOWEST) {
            // Get lowest bitrate
            return streams.stream()
                    .filter(s -> s.getContent() != null)
                    .min(Comparator.comparingInt(AudioStream::getAverageBitrate))
                    .orElse(streams.get(0));
        } else {
            // Middle quality - aim for ~128kbps
            final int targetBitrate = quality == Quality.LOW ? 64 : 128;
            return streams.stream()
                    .filter(s -> s.getContent() != null)
                    .min(Comparator.comparingInt(s ->
                            Math.abs(s.getAverageBitrate() - targetBitrate)))
                    .orElse(streams.get(0));
        }
    }

    /**
     * Extracts numeric resolution value from a VideoStream.
     *
     * @param stream The video stream
     * @return Resolution height in pixels, or 0 if unknown
     */
    private int getResolutionValue(final VideoStream stream) {
        final String resolution = stream.getResolution();
        if (resolution.isEmpty()) {
            // Try to get height directly if available
            final int height = stream.getHeight();
            return Math.max(height, 0);
        }
        return RESOLUTION_MAP.getOrDefault(resolution, this.parseResolution(resolution));
    }

    /**
     * Parses resolution string to extract numeric value.
     *
     * @param resolution Resolution string (e.g., "720p", "1080p60")
     * @return Parsed resolution value or 0 if unparseable
     */
    private int parseResolution(final String resolution) {
        if (resolution == null) return 0;
        final Matcher matcher = Pattern.compile("(\\d+)p").matcher(resolution);
        if (matcher.find()) {
            try {
                return Integer.parseInt(matcher.group(1));
            } catch (final NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    /**
     * Custom Downloader implementation for NewPipeExtractor using Java's HttpClient.
     * This is required by NewPipe to make HTTP requests to YouTube.
     */
    private static class WaterMediaDownloader extends Downloader {

        private final HttpClient httpClient;

        public WaterMediaDownloader() {
            this.httpClient = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
        }

        @Override
        public Response execute(final Request request) throws IOException {
            final String url = request.url();
            final Map<String, List<String>> headers = request.headers();
            final byte[] dataToSend = request.dataToSend();

            final HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(30));

            // Add headers
            if (headers != null) {
                for (final Map.Entry<String, List<String>> entry : headers.entrySet()) {
                    final String headerName = entry.getKey();
                    for (final String headerValue : entry.getValue()) {
                        builder.header(headerName, headerValue);
                    }
                }
            }

            // Set request method and body
            if (dataToSend != null && dataToSend.length > 0) {
                builder.POST(HttpRequest.BodyPublishers.ofByteArray(dataToSend));
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