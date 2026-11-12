package com.replaymod.core.mixin;

import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/** Forge port: used by ForgeScreens as a replacement for Fabric's Screens.getButtons. */
@Mixin(Screen.class)
public interface ScreenWidgetsAccessor {
    @Accessor("renderables")
    List<Renderable> replaymod$getRenderables();

    @Accessor("children")
    List<GuiEventListener> replaymod$getChildren();

    @Accessor("narratables")
    List<NarratableEntry> replaymod$getNarratables();
}
