package org.watermedia.api.platform.extension.bootstrap;

import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLLoader;

@Mod(value = WaterMediaExtension.ID)
public class NeoBootstrap {
    public NeoBootstrap() {
        WaterMediaExtension.start(FMLLoader.getDist().isClient());
    }
}
