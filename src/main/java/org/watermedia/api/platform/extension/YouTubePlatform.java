package org.watermedia.api.platform.extension;

import com.google.gson.JsonObject;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.extension.botguard.BotGuardClient;
import org.watermedia.api.platform.extension.botguard.BotGuardException;
import org.watermedia.api.platform.extension.ytdlp.YoutubeVisitorData;
import org.watermedia.api.platform.extension.ytdlp.YtDlpClient;
import org.watermedia.api.platform.extension.ytdlp.YtDlpExtractor;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import static org.watermedia.WaterMedia.LOGGER;

/**
 * YouTube platform implementation backed by yt-dlp. Resolves a YouTube URL to its direct video/audio
 * stream links plus metadata, thumbnails and captions by shelling out to the bundled yt-dlp binary
 * (which handles the signature and throttling-parameter deciphering YouTube requires) and mapping its
 * JSON to a {@link org.watermedia.api.platform.DataSource}.
 *
 * <p>If yt-dlp is blocked by the "Sign in to confirm you're not a bot" check and a BotGuard client is
 * available, the request is retried with a freshly minted po_token supplied to the web-family clients,
 * which unblocks their otherwise-gated stream URLs while keeping yt-dlp's bot-resistant default clients.
 */
public class YouTubePlatform implements IPlatform {
    public static final String NAME = "YouTube";
    private static final Marker IT = MarkerManager.getMarker(YouTubePlatform.class.getSimpleName());
    private static final String[] HOSTS = { "youtube.com", "youtu.be" };
    // VIDEO SHAPES (vs a pure playlist link) — DECIDES WHETHER TO PASS --no-playlist
    private static final Pattern YOUTUBE_VIDEO_ID = Pattern.compile("(?:youtu\\.be/|youtube\\.com/(?:embed/|v/|shorts/|feeds/api/videos/|watch\\?v=|watch\\?.+&v=))([^/?&#]+)");

    private final YtDlpClient ytdlp;
    private final BotGuardClient botGuard; // NULLABLE: po_token retry disabled when absent
    private final YtDlpExtractor extractor = new YtDlpExtractor(YouTubePlatform.class);

    public YouTubePlatform(final YtDlpClient ytdlp, final BotGuardClient botGuard) {
        this.ytdlp = ytdlp;
        this.botGuard = botGuard;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // NOT A YOUTUBE URL → LET PlatformAPI KEEP PROBING OTHER HANDLERS (HOSTS ARE CASE-INSENSITIVE)
        final String host = uri.getHost();
        if (host == null || !DataTool.endsWith(host.toLowerCase(Locale.ROOT), HOSTS)) {
            return null;
        }

        // A VIDEO LINK PLAYS THE VIDEO (EVEN IF IT CARRIES &list=); A PURE PLAYLIST LINK PLAYS THE PLAYLIST
        final List<String> args = new ArrayList<>();
        if (YOUTUBE_VIDEO_ID.matcher(uri.toString()).find()) {
            args.add("--no-playlist");
        }

        try {
            return this.extractor.toPlatformData(this.ytdlp.info(uri.toString(), args));
        } catch (final BotGuardException e) {
            if (this.botGuard != null && isBotCheck(e.getMessage())) {
                return this.retryWithPoToken(uri, args);
            }
            throw new PlatformException(YouTubePlatform.class, "yt-dlp could not resolve " + uri + ": " + e.getMessage(), e);
        }
    }

    // RETRIES WITH A PO_TOKEN BOUND TO A FRESH visitorData, SUPPLIED TO EVERY WEB-FAMILY gvs CONTEXT
    // (THE BotGuard WEB TOKEN COVERS web/web_safari/mweb/tv). NO player_client OVERRIDE: yt-dlp KEEPS ITS
    // BOT-RESISTANT DEFAULTS (android_vr, web_safari) AND THE TOKEN UNBLOCKS THE WEB CLIENT'S GATED STREAMS
    // — FORCING ONLY THE WEB CLIENTS YIELDS NO FORMATS. SURFACES THE ORIGINAL ERROR IF THE RETRY ALSO FAILS.
    private PlatformData retryWithPoToken(final URI uri, final List<String> baseArgs) throws Exception {
        try {
            final String visitorData = YoutubeVisitorData.fetch();
            final String token = this.botGuard.mint(visitorData);
            final List<String> args = new ArrayList<>(baseArgs);
            args.add("--extractor-args");
            args.add("youtube:po_token=web.gvs+" + token + ",web_safari.gvs+" + token
                    + ",mweb.gvs+" + token + ",tv.gvs+" + token
                    + ";visitor_data=" + visitorData + ";player_skip=webpage,configs");
            args.add("--extractor-args");
            args.add("youtubetab:skip=webpage");
            LOGGER.info(IT, "Retrying '{}' with a BotGuard po_token", uri);
            return this.extractor.toPlatformData(this.ytdlp.info(uri.toString(), args));
        } catch (final Exception retry) {
            throw new PlatformException(YouTubePlatform.class,
                    "YouTube blocked this request and the po_token retry failed: " + retry.getMessage(), retry);
        }
    }

    // yt-dlp'S MESSAGES FOR THE BOT/AGE GATE THAT A PO_TOKEN CAN BYPASS
    private static boolean isBotCheck(final String message) {
        if (message == null) return false;
        final String m = message.toLowerCase(Locale.ROOT);
        return m.contains("not a bot") || m.contains("sign in to confirm") || m.contains("po_token")
                || m.contains("confirm your age");
    }
}
