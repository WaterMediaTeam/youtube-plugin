package org.watermedia.youtube.loaders;

import net.neoforged.fml.common.Mod;
import org.watermedia.youtube.WaterMediaYT;

@Mod(value = WaterMediaYT.ID)
public class NeoFLoader {
    public NeoFLoader() {
        WaterMediaYT.start();
    }
}
