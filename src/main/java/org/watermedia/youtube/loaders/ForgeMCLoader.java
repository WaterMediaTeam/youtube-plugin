package org.watermedia.youtube.loaders;

import net.minecraftforge.fml.common.Mod;
import org.watermedia.youtube.WaterMediaYT;

@Mod(value = WaterMediaYT.ID)
public class ForgeMCLoader {
    public ForgeMCLoader() {
        WaterMediaYT.start();
    }
}
