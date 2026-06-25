package org.watermedia.api.platform.extension;

import org.watermedia.api.platform.IPlatform;
import org.watermedia.api.platform.PlatformData;
import org.watermedia.api.platform.PlatformException;
import org.watermedia.api.platform.extension.botguard.BotGuardException;
import org.watermedia.api.platform.extension.ytdlp.YtDlpClient;
import org.watermedia.api.platform.extension.ytdlp.YtDlpExtractor;
import org.watermedia.tools.DataTool;

import java.net.URI;
import java.util.List;
import java.util.Locale;

/**
 * Generic yt-dlp platform: a single handler for every site whose extraction needs nothing beyond
 * yt-dlp's defaults (SoundCloud, Facebook, Instagram, Newgrounds, ...). yt-dlp emits the same JSON shape
 * for all of them, so one shared {@link YtDlpExtractor} maps them uniformly. YouTube keeps its own
 * handler ({@link YouTubePlatform}) because it additionally needs the BotGuard po_token retry. Supporting
 * a new site is a one-line change: add its host to {@link #HOSTS}.
 */
public class YtDlpPlatform implements IPlatform {
    public static final String NAME = "yt-dlp";
    // HOST SUFFIXES, MATCHED CASE-INSENSITIVELY WITH endsWith SO SUBDOMAINS (www./m./web.) ARE COVERED
    private static final String[] HOSTS = {
            "soundcloud.com", "snd.sc",     // SOUNDCLOUD
            "facebook.com", "fb.watch",     // FACEBOOK
            "instagram.com", "instagr.am",  // INSTAGRAM
            "newgrounds.com",               // NEWGROUNDS
    };

    private final YtDlpClient ytdlp;
    private final YtDlpExtractor extractor = new YtDlpExtractor(YtDlpPlatform.class);

    public YtDlpPlatform(final YtDlpClient ytdlp) {
        this.ytdlp = ytdlp;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public PlatformData getData(final URI uri) throws Exception {
        // NONE OF OUR HOSTS → LET PlatformAPI KEEP PROBING OTHER HANDLERS (HOSTS ARE CASE-INSENSITIVE)
        final String host = uri.getHost();
        if (host == null || !DataTool.endsWith(host.toLowerCase(Locale.ROOT), HOSTS)) {
            return null;
        }
        try {
            return this.extractor.toPlatformData(this.ytdlp.info(uri.toString(), List.of()));
        } catch (final BotGuardException e) {
            throw new PlatformException(YtDlpPlatform.class, "yt-dlp could not resolve " + uri + ": " + e.getMessage(), e);
        }
    }
}
