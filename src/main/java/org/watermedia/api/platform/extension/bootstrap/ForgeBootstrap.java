package org.watermedia.api.platform.extension.bootstrap;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLLoader;

@Mod(value = WaterMediaExtension.ID)
public class ForgeBootstrap {
    public ForgeBootstrap() {
        WaterMediaExtension.start(FMLLoader.getDist().isClient());
    }
}
