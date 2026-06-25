package org.watermedia.api.platform.extension.ytdlp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.watermedia.api.platform.DataQuality;
import org.watermedia.api.platform.DataSlave;
import org.watermedia.api.platform.DataSource;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.MatureContentException;
import org.watermedia.WaterMediaConfig;
import org.watermedia.api.util.MediaType;
import org.watermedia.api.util.Metadata;
import org.watermedia.api.util.RequestHeaders;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts yt-dlp's {@code --dump-single-json} output into WaterMedia {@link DataSource}s. A single media
 * object becomes one source; a playlist ({@code entries[]}) becomes several. The same schema covers
 * YouTube and SoundCloud, so both platforms share this code.
 *
 * <p>yt-dlp already deciphers the signature and throttling (n) parameters, so {@code formats[].url} is
 * playable directly — but each URL is bound to the {@code http_headers} (notably {@code User-Agent}) that
 * yt-dlp used, which are copied into the source's {@link RequestHeaders} so the player sends them.
 */
public final class YtDlpExtractor {
    private static final Pattern RESOLUTION = Pattern.compile("(\\d+)p");
    private static final Pattern HEIGHT_IN_RES = Pattern.compile("\\d+x(\\d+)");
    private static final long LIVE_TTL_SECONDS = 30 * 60;
    private static final long FALLBACK_TTL_SECONDS = 4 * 3600;

    private final Class<? extends IPlatform> platform;

    public YtDlpExtractor(final Class<? extends IPlatform> platform) {
        this.platform = platform;
    }

    /** Builds the {@link PlatformData} (single media or playlist) from yt-dlp's JSON. */
    public PlatformData toPlatformData(final JsonObject info) throws Exception {
        final JsonArray entries = info.getAsJsonArray("entries");
        if (entries == null) {
            final Result r = this.single(info);
            return new PlatformData(r.expires(), r.source());
        }

        final List<DataSource> sources = new ArrayList<>();
        Instant earliest = null;
        for (final JsonElement e : entries) {
            if (e == null || e.isJsonNull()) continue; // yt-dlp NULLS OUT UNAVAILABLE PLAYLIST ITEMS
            try {
                final Result r = this.single(e.getAsJsonObject());
                sources.add(r.source());
                if (earliest == null || (r.expires() != null && r.expires().isBefore(earliest))) {
                    earliest = r.expires();
                }
            } catch (final Exception ignored) {
                // SKIP UNRESOLVABLE PLAYLIST ITEMS
            }
        }
        if (sources.isEmpty()) {
            throw new PlatformException(this.platform, "No playable entries in playlist");
        }
        return new PlatformData(earliest, sources.toArray(DataSource[]::new));
    }

    private Result single(final JsonObject media) throws Exception {
        // RESPECT THE MATURE-CONTENT GATE (yt-dlp REPORTS age_limit; >=18 IS AGE-RESTRICTED)
        if (intOr(media, "age_limit", 0) >= 18 && !WaterMediaConfig.platforms.allowMatureContent) {
            throw new MatureContentException(this.platform, "Age-restricted: " + str(media, "webpage_url"));
        }

        final JsonArray formats = media.getAsJsonArray("formats");
        if (formats == null || formats.isEmpty()) {
            throw new PlatformException(this.platform, "No formats for " + str(media, "webpage_url"));
        }

        final URI sourceUri = uri(str(media, "webpage_url"));
        final boolean live = bool(media, "is_live") || "is_live".equals(str(media, "live_status"));
        final URI thumbnail = uri(str(media, "thumbnail"));
        final Metadata metadata = this.metadata(media);
        final List<DataSlave> subtitles = subtitles(media);

        // SPLIT FORMATS: VIDEO-ONLY (acodec none), MUXED (both), AUDIO-ONLY (vcodec none). SKIP STORYBOARDS.
        final List<DataQuality> variants = new ArrayList<>();
        final Set<Integer> seenHeights = new HashSet<>();
        JsonObject headerSource = null;
        JsonObject bestAudio = null;
        int bestAudioRate = -1;

        // VIDEO-ONLY FIRST (BEST QUALITY, AUDIO CARRIED BY A SLAVE), THEN MUXED FILLS REMAINING TIERS
        for (int pass = 0; pass < 2; pass++) {
            for (final JsonElement fe : formats) {
                final JsonObject f = fe.getAsJsonObject();
                if (!playable(f)) continue;
                final boolean hasVideo = !"none".equals(str(f, "vcodec"));
                final boolean hasAudio = !"none".equals(str(f, "acodec"));
                if (!hasVideo) {
                    if (pass == 0) {
                        final int rate = audioRate(f);
                        if (rate > bestAudioRate) { bestAudio = f; bestAudioRate = rate; }
                    }
                    continue;
                }
                final boolean videoOnly = !hasAudio;
                if ((pass == 0) != videoOnly) continue; // PASS 0 = VIDEO-ONLY, PASS 1 = MUXED
                final int height = heightOf(f);
                if (height > 0 && !seenHeights.add(height)) continue;
                final int width = intOr(f, "width", height > 0 ? height * 16 / 9 : 0);
                variants.add(new DataQuality(uri(str(f, "url")), width, height));
                if (headerSource == null) headerSource = f;
            }
        }

        final RequestHeaders headers = headers(headerSource != null ? headerSource : bestAudio, sourceUri);

        if (!variants.isEmpty()) {
            final List<DataSlave> audioSlaves = bestAudio == null ? null
                    : List.of(new DataSlave(null, null, uri(str(bestAudio, "url"))));
            final DataSource source = new DataSource(MediaType.VIDEO, thumbnail, metadata, headers,
                    variants.toArray(DataQuality[]::new), audioSlaves, subtitles);
            return new Result(expiry(variants.get(0).uri(), live), source);
        }

        // AUDIO-ONLY (SOUNDCLOUD, YOUTUBE MUSIC)
        if (bestAudio != null) {
            final URI audio = uri(str(bestAudio, "url"));
            final DataSource source = new DataSource(MediaType.AUDIO, thumbnail, metadata, headers,
                    new DataQuality[] { new DataQuality(audio, 0, 0) }, null, subtitles);
            return new Result(expiry(audio, live), source);
        }

        throw new PlatformException(this.platform, "No usable streams for " + str(media, "webpage_url"));
    }

    // A FORMAT IS PLAYABLE IF IT CARRIES MEDIA (NOT A STORYBOARD/THUMBNAIL TRACK) AND HAS A URL
    private static boolean playable(final JsonObject f) {
        if (str(f, "url") == null) return false;
        if ("mhtml".equals(str(f, "protocol"))) return false; // STORYBOARDS
        final boolean hasVideo = !"none".equals(str(f, "vcodec"));
        final boolean hasAudio = !"none".equals(str(f, "acodec"));
        if (f.get("vcodec") == null && f.get("acodec") == null) return false;
        return hasVideo || hasAudio;
    }

    private static int audioRate(final JsonObject f) {
        final double abr = dbl(f, "abr");
        return (int) Math.round(abr > 0 ? abr : dbl(f, "tbr"));
    }

    private static int heightOf(final JsonObject f) {
        final Integer h = intOrNull(f, "height");
        if (h != null) return h;
        final String res = str(f, "resolution");
        if (res != null) {
            final Matcher m = HEIGHT_IN_RES.matcher(res);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        final String note = str(f, "format_note");
        if (note != null) {
            final Matcher m = RESOLUTION.matcher(note);
            if (m.find()) return Integer.parseInt(m.group(1));
        }
        return 0;
    }

    // COPIES yt-dlp'S PER-FORMAT http_headers (User-Agent ETC.) INTO RequestHeaders SO THE PLAYER SENDS
    // THEM — THE GOOGLEVIDEO URLS 403 WITHOUT THE MATCHING UA. FALLS BACK TO WATERMEDIA DEFAULTS.
    private static RequestHeaders headers(final JsonObject format, final URI sourceUri) {
        final JsonObject hh = format == null ? null : format.getAsJsonObject("http_headers");
        if (hh == null || hh.isEmpty()) {
            return sourceUri == null ? new RequestHeaders() : RequestHeaders.defaults(sourceUri);
        }
        final RequestHeaders headers = new RequestHeaders();
        for (final Map.Entry<String, JsonElement> e : hh.entrySet()) {
            if (!e.getValue().isJsonNull()) {
                headers.set(e.getKey(), e.getValue().getAsString());
            }
        }
        return headers;
    }

    private Metadata metadata(final JsonObject media) {
        final String title = str(media, "title");
        String author = str(media, "uploader");
        if (author == null) author = str(media, "channel");
        final String description = str(media, "description");
        final long durationMs = (long) (dbl(media, "duration") * 1000);

        Instant publishedAt = null;
        final JsonElement ts = media.get("timestamp");
        if (ts != null && !ts.isJsonNull()) {
            publishedAt = Instant.ofEpochSecond(ts.getAsLong());
        } else {
            final String date = str(media, "upload_date"); // YYYYMMDD
            if (date != null && date.length() == 8) {
                try {
                    publishedAt = LocalDate.of(Integer.parseInt(date.substring(0, 4)),
                            Integer.parseInt(date.substring(4, 6)), Integer.parseInt(date.substring(6, 8)))
                            .atStartOfDay(ZoneOffset.UTC).toInstant();
                } catch (final Exception ignored) {
                    // LEAVE UNSET
                }
            }
        }
        return new Metadata(title, description, publishedAt, durationMs, author);
    }

    // BUILDS SUBTITLE SLAVES FROM subtitles{} (MANUAL) THEN automatic_captions{} (AUTO), ONE PER LANGUAGE
    private static List<DataSlave> subtitles(final JsonObject media) {
        final List<DataSlave> out = new ArrayList<>();
        final Set<String> langs = new HashSet<>();
        collectSubs(media.getAsJsonObject("subtitles"), false, langs, out);
        collectSubs(media.getAsJsonObject("automatic_captions"), true, langs, out);
        return out.isEmpty() ? null : out;
    }

    private static void collectSubs(final JsonObject subs, final boolean auto,
                                    final Set<String> langs, final List<DataSlave> out) {
        if (subs == null) return;
        for (final Map.Entry<String, JsonElement> e : subs.entrySet()) {
            final String lang = e.getKey();
            if (!langs.add(lang) || !e.getValue().isJsonArray()) continue;
            final JsonArray tracks = e.getValue().getAsJsonArray();
            if (tracks.isEmpty()) continue;
            final JsonObject track = tracks.get(0).getAsJsonObject();
            final String url = str(track, "url");
            if (url == null) continue;
            String name = str(track, "name");
            if (name == null) name = lang;
            if (auto) name = name + " (auto-generated)";
            out.add(new DataSlave(name, lang, uri(url)));
        }
    }

    // READS THE expire=<epoch> PARAM FROM A GOOGLEVIDEO/CDN URL; LIVE OR MISSING → SHORT/FALLBACK TTL
    private static Instant expiry(final URI uri, final boolean live) {
        if (live) return Instant.now().plusSeconds(LIVE_TTL_SECONDS);
        if (uri != null) {
            final String query = uri.getRawQuery();
            if (query != null) {
                for (final String param : query.split("&")) {
                    if (param.startsWith("expire=")) {
                        try {
                            return Instant.ofEpochSecond(Long.parseLong(param.substring(7)));
                        } catch (final NumberFormatException ignored) {
                            // FALL THROUGH
                        }
                    }
                }
            }
        }
        return Instant.now().plusSeconds(FALLBACK_TTL_SECONDS);
    }

    // ---- Gson accessors (null-safe) ----
    private static String str(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsString();
    }
    private static double dbl(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? 0d : e.getAsDouble();
    }
    private static boolean bool(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return e != null && !e.isJsonNull() && e.getAsBoolean();
    }
    private static Integer intOrNull(final JsonObject o, final String key) {
        final JsonElement e = o.get(key);
        return (e == null || e.isJsonNull()) ? null : e.getAsInt();
    }
    private static int intOr(final JsonObject o, final String key, final int def) {
        final Integer v = intOrNull(o, key);
        return v == null ? def : v;
    }
    private static URI uri(final String s) {
        return s == null ? null : URI.create(s);
    }

    // PAIRS A RESOLVED SOURCE WITH ITS EXPIRATION
    private record Result(Instant expires, DataSource source) {}
}
