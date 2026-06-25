package org.watermedia.api.platform.extension.bootstrap;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.loader.api.FabricLoader;

public class FabricBootstrap implements ModInitializer {
    @Override
    public void onInitialize() {
        WaterMediaExtension.start(FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT);
    }
}
