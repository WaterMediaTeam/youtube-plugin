package org.watermedia.youtube;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.localization.Localization;
import org.watermedia.api.media.MediaAPI;
import org.watermedia.youtube.patch.YouTubePlatform;

import java.util.Locale;

public class WaterMediaYT {
    public static final String ID = "watermedia_youtube_extension";

    public static void start() {
        NewPipe.init(new YouTubePlatform.WaterMediaDownloader(), Localization.fromLocale(Locale.getDefault()));
        MediaAPI.registerPlatform(new YouTubePlatform(), org.watermedia.api.media.platform.YoutubePlatform.class);
    }
}
