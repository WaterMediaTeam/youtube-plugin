package org.watermedia.youtube.bootstrap;

import net.minecraftforge.fml.common.Mod;
import org.watermedia.youtube.WaterMediaYT;

@Mod(value = WaterMediaYT.ID)
public class ForgeBootstrap {
    public ForgeBootstrap() {
        WaterMediaYT.start();
    }
}
