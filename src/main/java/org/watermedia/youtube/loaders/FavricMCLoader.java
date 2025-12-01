package org.watermedia.youtube.loaders;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.ModInitializer;
import org.watermedia.WaterMedia;
import org.watermedia.youtube.WaterMediaYT;

public class FavricMCLoader implements ClientModInitializer, ModInitializer {
    @Override
    public void onInitializeClient() {
        WaterMediaYT.start();
    }


    @Override
    public void onInitialize() {
        WaterMedia.LOGGER.warn("Stub running successfully");
    }
}
