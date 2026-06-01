package org.watermedia.youtube.bootstrap;

import org.watermedia.bootstrap.AppBootstrap;
import org.watermedia.youtube.WaterMediaYT;

public class ExtensionBootstrap implements AppBootstrap.Extension {
    @Override
    public void load() {
        WaterMediaYT.start();
    }
}
