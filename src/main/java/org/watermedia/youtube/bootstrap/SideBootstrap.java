package org.watermedia.youtube.bootstrap;

import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.youtube.WaterMediaYT;

public class SideBootstrap implements AppBootstrap.Sideloadable {
    @Override
    public void load() {
        WaterMediaYT.start();
    }
}
