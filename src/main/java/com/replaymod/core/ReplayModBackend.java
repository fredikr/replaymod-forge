package com.replaymod.core;

import com.replaymod.core.forge.ForgePlatform;
import net.minecraft.SharedConstants;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

import static com.replaymod.core.ReplayMod.MOD_ID;

@Mod(MOD_ID)
public class ReplayModBackend {
    private final ReplayMod mod;

    public ReplayModBackend(FMLJavaModLoadingContext context) {
        ForgePlatform.init();
        com.replaymod.core.forge.ViaMappings.preload();
        mod = new ReplayMod(this);
        mod.initModules();
    }

    public String getVersion() {
        String version = ForgePlatform.getModVersion(MOD_ID);
        if (version == null) throw new IllegalStateException();
        return version;
    }

    public String getMinecraftVersion() {
        return SharedConstants.getCurrentVersion().name();
    }

    public boolean isModLoaded(String id) {
        return ForgePlatform.isModLoaded(id.toLowerCase());
    }
}
