package com.replaymod.core.forge;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import de.johni0702.minecraft.gui.function.CharInput;
import de.johni0702.minecraft.gui.function.KeyInput;
import de.johni0702.minecraft.gui.versions.callbacks.KeyboardCallback;
import de.johni0702.minecraft.gui.versions.callbacks.PostRenderScreenCallback;
import de.johni0702.minecraft.gui.versions.callbacks.RenderHudCallback;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.eventbus.api.listener.Priority;
import net.minecraft.resources.Identifier;
import net.minecraftforge.client.event.AddGuiOverlayLayersEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.gui.overlay.ForgeLayeredDraw;
import net.minecraftforge.fml.ModList;
import org.apache.commons.lang3.ArrayUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Forge replacements for the bits of Fabric Loader / Fabric API which ReplayMod relies on.
 */
public final class ForgePlatform {
    private static final List<KeyMapping> pendingKeyMappings = new ArrayList<>();
    private static boolean keyMappingsRegistered;

    private ForgePlatform() {}

    /** Must be called once from the mod constructor. */
    public static void init() {
        RegisterKeyMappingsEvent.BUS.addListener(event -> {
            synchronized (pendingKeyMappings) {
                pendingKeyMappings.forEach(event::register);
                pendingKeyMappings.clear();
                keyMappingsRegistered = true;
            }
        });

        // Forge renders the HUD through ForgeLayeredDraw (vanilla Gui#render returns early), so jGui's
        // Mixin_RenderHudCallback never runs. Fire the same callback from a layer at the same spot instead:
        // right before the demo overlay, in the stack which is hidden with F1.
        AddGuiOverlayLayersEvent.BUS.addListener(event -> event.getLayeredDraw().addBelow(
                ForgeLayeredDraw.POST_SLEEP_STACK,
                Identifier.fromNamespaceAndPath("replaymod", "hud_callback"),
                ForgeLayeredDraw.DEMO_OVERLAY,
                (graphics, deltaTracker) -> RenderHudCallback.EVENT.invoker()
                        .renderHud(graphics, deltaTracker.getGameTimeDeltaPartialTick(true))));

        // jGui's screen hooks (MixinGameRenderer / MixinKeyboardListener in the original) are done through Forge's
        // screen events instead of mixins: Forge fires these from ForgeHooksClient, which is also what OptiFine calls
        // (via reflection) in its replacement GameRenderer / KeyboardHandler, so there is no call site to inject into.
        ScreenEvent.Render.Post.BUS.addListener(event -> {
            // ForgeHooksClient#drawScreen also draws the layered screens below the current one; only fire once,
            // after the current screen, like the original did.
            if (event.getScreen() == Minecraft.getInstance().screen) {
                PostRenderScreenCallback.EVENT.invoker().postRenderScreen(event.getGuiGraphics(), event.getPartialTick());
            }
        });
        // Returning true cancels the Pre event, which makes Forge skip the screen's own handler (as the original did).
        ScreenEvent.KeyPressed.Pre.BUS.addListener(Priority.HIGHEST, (Predicate<ScreenEvent.KeyPressed.Pre>)
                event -> KeyboardCallback.EVENT.invoker().keyPressed(new KeyInput(event.getInfo())));
        ScreenEvent.KeyReleased.Pre.BUS.addListener(Priority.HIGHEST, (Predicate<ScreenEvent.KeyReleased.Pre>)
                event -> KeyboardCallback.EVENT.invoker().keyReleased(new KeyInput(event.getInfo())));
        ScreenEvent.CharacterTyped.Pre.BUS.addListener(Priority.HIGHEST, (Predicate<ScreenEvent.CharacterTyped.Pre>)
                event -> KeyboardCallback.EVENT.invoker().charTyped(new CharInput(event.getInfo())));
    }

    public static void registerKeyMapping(KeyMapping keyMapping) {
        synchronized (pendingKeyMappings) {
            if (!keyMappingsRegistered) {
                pendingKeyMappings.add(keyMapping);
                return;
            }
        }
        // Too late for the event, add it to the options directly
        Options options = Minecraft.getInstance().options;
        if (options != null) {
            options.keyMappings = ArrayUtils.add(options.keyMappings, keyMapping);
        }
    }

    public static boolean isModLoaded(String id) {
        ModList modList = ModList.get();
        return modList != null && modList.isLoaded(id);
    }

    public static String getModVersion(String id) {
        ModList modList = ModList.get();
        if (modList == null) return null;
        return modList.getModContainerById(id)
                .map(container -> container.getModInfo().getVersion().toString())
                .orElse(null);
    }

    /** Resolves a path inside of the jar (or dev folder) of the given mod. */
    public static Path findResource(String modId, String... path) {
        return ModList.get().getModFileById(modId).getFile().findResource(path);
    }
}
