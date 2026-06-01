package org.watermedia.youtube.patch;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.stream.AudioStream;
import org.schabi.newpipe.extractor.stream.DeliveryMethod;
import org.schabi.newpipe.extractor.stream.StreamExtractor;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * SoundCloud platform implementation using NewPipeExtractor.
 * Resolves SoundCloud tracks and sets (playlists) to direct audio stream URLs.
 *
 * <p>This platform supports:
 * <ul>
 *     <li>Track URLs (soundcloud.com/{user}/{track})</li>
 *     <li>Set / playlist URLs (soundcloud.com/{user}/sets/{name})</li>
 * </ul>
 *
 * <p>SoundCloud is audio-only, so every resolved entry is a {@link MediaType#AUDIO} source.
 * The progressive (direct HTTP) rendition is preferred over HLS for broader player
 * compatibility, falling back to the highest available bitrate.
 *
 * <p>NewPipe must be initialised (see {@link YouTubePlatform.WaterMediaDownloader}) before
 * this platform is used; the SoundCloud service shares that downloader.
 */
public class SoundCloudPlatform implements IPlatform {
    private static final Marker IT = MarkerManager.getMarker(SoundCloudPlatform.class.getSimpleName());
    private static final Duration TTL = Duration.ofHours(1);

    @Override
    public String name() {
        return "SoundCloud";
    }

    @Override
    public boolean validate(final URI uri) {
        final String host = uri.getHost();
        return host != null && (host.endsWith("soundcloud.com") || host.equalsIgnoreCase("snd.sc"));
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        final StreamingService soundcloud = ServiceList.SoundCloud;
        final String url = uri.toString();

        // Sets/playlists must be checked first: their URLs also satisfy the track shape
        if (soundcloud.getPlaylistLHFactory().acceptUrl(url)) {
            return this.playlist(soundcloud, url);
        }

        if (soundcloud.getStreamLHFactory().acceptUrl(url)) {
            final var extractor = soundcloud.getStreamExtractor(url);
            extractor.fetchPage();
            final Result r = this.track(extractor);
            return new PlatformData(r.expires(), r.source());
        }

        throw new IllegalArgumentException("Unsupported SoundCloud URL: " + uri);
    }

    /**
     * Builds an AUDIO source for a single SoundCloud track.
     */
    private Result track(final StreamExtractor extractor) throws Exception {
        final List<AudioStream> audioStreams = extractor.getAudioStreams();
        if (audioStreams == null || audioStreams.isEmpty()) {
            throw new IllegalStateException("No audio streams available for " + extractor.getUrl());
        }

        final URI audioUri = URI.create(bestAudioStream(audioStreams).getContent());

        final var source = new DataSource(
                MediaType.AUDIO,
                thumbnail(extractor),
                metadata(extractor),
                RequestHeaders.defaults(URI.create(extractor.getUrl())),
                new DataQuality[] { new DataQuality(audioUri, 0, 0) },
                null,
                null);

        return new Result(Instant.now().plus(TTL), source);
    }

    /**
     * Builds AUDIO sources for every track in a SoundCloud set (playlist).
     */
    private PlatformData playlist(final StreamingService soundcloud, final String url) throws Exception {
        final var playlistExtractor = soundcloud.getPlaylistExtractor(url);
        playlistExtractor.fetchPage();

        // Collect all stream items across all pages
        var page = playlistExtractor.getInitialPage();
        final var allItems = new ArrayList<>(page.getItems());
        while (page.hasNextPage()) {
            page = playlistExtractor.getPage(page.getNextPage());
            allItems.addAll(page.getItems());
        }

        if (allItems.isEmpty()) {
            throw new IllegalStateException("SoundCloud set is empty: " + url);
        }

        final var sources = new ArrayList<DataSource>();
        Instant earliestExpiration = null;

        for (final var item : allItems) {
            try {
                final var streamExtractor = soundcloud.getStreamExtractor(item.getUrl());
                streamExtractor.fetchPage();

                final Result itemResult = this.track(streamExtractor);
                sources.add(itemResult.source());
                if (earliestExpiration == null
                        || (itemResult.expires() != null && itemResult.expires().isBefore(earliestExpiration))) {
                    earliestExpiration = itemResult.expires();
                }
            } catch (final Exception e) {
                LOGGER.warn(IT, "Skipping SoundCloud set item '{}': {}", item.getName(), e.getMessage());
            }
        }

        if (sources.isEmpty()) {
            throw new IllegalStateException("No tracks could be extracted from SoundCloud set: " + url);
        }

        return new PlatformData(earliestExpiration, sources.toArray(DataSource[]::new));
    }

    /**
     * Internal holder pairing a single resolved {@link DataSource} with its expiration,
     * so the set/playlist path can aggregate multiple entries and compute the earliest one.
     */
    private record Result(Instant expires, DataSource source) {}

    // =========================================================================
    // STREAM SELECTION
    // =========================================================================

    /**
     * Picks the best audio stream: progressive (direct HTTP) renditions are preferred over
     * HLS/DASH for player compatibility, and among equal delivery methods the highest
     * average bitrate wins. Falls back to the first stream when no preference applies.
     */
    private static AudioStream bestAudioStream(final List<AudioStream> streams) {
        return streams.stream()
                .max(Comparator
                        .comparingInt(SoundCloudPlatform::deliveryRank)
                        .thenComparingInt(AudioStream::getAverageBitrate))
                .orElse(streams.get(0));
    }

    /**
     * Ranks delivery methods so progressive HTTP (a direct, seekable file) is preferred.
     */
    private static int deliveryRank(final AudioStream stream) {
        return stream.getDeliveryMethod() == DeliveryMethod.PROGRESSIVE_HTTP ? 1 : 0;
    }

    // =========================================================================
    // METADATA
    // =========================================================================

    /**
     * Extracts the highest-resolution artwork URI from the StreamExtractor, or {@code null}.
     */
    private static URI thumbnail(final StreamExtractor extractor) {
        try {
            final var thumbs = extractor.getThumbnails();
            if (!thumbs.isEmpty()) {
                // Last thumbnail is typically highest resolution
                return URI.create(thumbs.get(thumbs.size() - 1).getUrl());
            }
        } catch (final Exception ignored) {}
        return null;
    }

    /**
     * Extracts track metadata from the StreamExtractor.
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
}
