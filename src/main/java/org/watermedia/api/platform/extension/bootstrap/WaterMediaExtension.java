package org.watermedia.api.platform.extension.bootstrap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.api.platform.extension.YouTubePlatform;
import org.watermedia.api.platform.extension.YtDlpPlatform;
import org.watermedia.api.platform.extension.botguard.BotGuardClient;
import org.watermedia.api.platform.extension.ytdlp.YtDlpClient;
import org.watermedia.bootstrap.AppBootstrap;

/**
 * Entry point shared by every loader (Forge, NeoForge, Fabric and the standalone WaterMedia app).
 * Registers two yt-dlp-backed handlers into {@link PlatformAPI}: {@link YouTubePlatform} (which adds the
 * BotGuard po_token retry) and the generic {@link YtDlpPlatform} (SoundCloud, Facebook, Instagram,
 * Newgrounds, ...).
 * Registration is client-only: these platforms resolve media for playback, which never happens
 * server-side. The yt-dlp and BotGuard binaries are resolved lazily on first use, so this adds no
 * startup cost or download.
 */
public class WaterMediaExtension implements AppBootstrap.Extension {
    // MUST MATCH THE modId DECLARED IN mods.toml / neoforge.mods.toml (gradle property "id")
    public static final String ID = "watermedia_platform_extension";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    @Override
    public void load() {
        // THE STANDALONE WATERMEDIA APP IS ALWAYS A CLIENT
        start(true);
    }

    public static void start(final boolean client) {
        if (!client) return; // CLIENT-ONLY: NO MEDIA PLAYBACK HAPPENS SERVER-SIDE
        LOGGER.info("Registering platform extension (YouTube + generic yt-dlp) via yt-dlp");

        final YtDlpClient ytdlp = new YtDlpClient();
        // BOTGUARD PROVIDES A po_token FOR THE mweb RETRY WHEN YOUTUBE BLOCKS WITH THE BOT CHECK
        final BotGuardClient botGuard = new BotGuardClient();

        PlatformAPI.register(new YouTubePlatform(ytdlp, botGuard));
        PlatformAPI.register(new YtDlpPlatform(ytdlp));
    }
}
