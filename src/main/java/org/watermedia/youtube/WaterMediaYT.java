package org.watermedia.youtube;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.Localization;
import org.watermedia.WaterMedia;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.api.platform.PlatformAPI;
import org.watermedia.tools.ThreadTool;
import org.watermedia.youtube.patch.SoundCloudPlatform;
import org.watermedia.youtube.patch.YouTubePlatform;

import java.util.Locale;

public class WaterMediaYT {
    public static final String ID = "watermedia_youtube_extension";
    public static final Logger LOGGER = LogManager.getLogger(ID);

    public static void start() {
        LOGGER.info("Registering YT extension");
        NewPipe.init(new YouTubePlatform.WaterMediaDownloader(), Localization.fromLocale(Locale.getDefault()));
        PlatformAPI.register(new YouTubePlatform());
        PlatformAPI.register(new SoundCloudPlatform());
    }
}
