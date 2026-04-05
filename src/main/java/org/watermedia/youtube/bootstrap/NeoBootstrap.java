package org.watermedia.youtube.bootstrap;

import net.neoforged.fml.common.Mod;
import org.watermedia.youtube.WaterMediaYT;

@Mod(value = WaterMediaYT.ID)
public class NeoBootstrap {
    public NeoBootstrap() {
        WaterMediaYT.start();
    }
}
