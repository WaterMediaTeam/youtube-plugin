package org.watermedia.youtube;

import org.watermedia.api.network.NetworkAPI;
import org.watermedia.youtube.patch.YouTubePatch;

public class WaterMediaYT {
    public static final String ID = "watermedia_youtube_plugin";

    public static void start() {
        NetworkAPI.registerPatch(new YouTubePatch());
    }
}
