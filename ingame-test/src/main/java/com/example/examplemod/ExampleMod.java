package com.example.examplemod;

import com.mojang.logging.LogUtils;
import com.replaymod.core.ReplayMod;
import com.replaymod.replay.ReplayHandler;
import com.replaymod.replay.ReplayModReplay;
import com.replaymod.replay.ReplaySender;
import com.replaymod.replaystudio.replay.ReplayFile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Automated end-to-end smoke test for the ReplayMod Forge port (test environment only, not shipped).
 * Creates a world, plays for a bit (ReplayMod records), leaves, opens the recording, seeks, closes it, quits.
 * Every step logs a line starting with [RMTEST].
 */
@Mod(ExampleMod.MODID)
public final class ExampleMod {
    public static final String MODID = "examplemod";
    private static final Logger LOGGER = LogUtils.getLogger();

    private int state = 0;
    private int ticks = 0;
    private long stateStart = System.currentTimeMillis();
    private int step = 0; // one-shot actions within a state
    private long lastPoll = 0;
    private long lastSize = -2;
    private static final long RUN_START = System.currentTimeMillis();
    private Path replay;
    private int jumpTarget;
    private int screenOverlayRenders = 0; // jGui PostRenderScreenCallback (overlays on vanilla screens)
    private int hudRenders = 0; // jGui RenderHudCallback (recording indicator)

    public ExampleMod(FMLJavaModLoadingContext context) {
        new de.johni0702.minecraft.gui.utils.EventRegistrations()
                .on(de.johni0702.minecraft.gui.versions.callbacks.PostRenderScreenCallback.EVENT,
                        (ctx, partialTicks) -> screenOverlayRenders++)
                .on(de.johni0702.minecraft.gui.versions.callbacks.RenderHudCallback.EVENT,
                        (ctx, partialTicks) -> hudRenders++)
                .register();
        TickEvent.RenderTickEvent.Post.BUS.addListener(e -> {
            try {
                tick();
            } catch (Throwable t) {
                LOGGER.error("[RMTEST] FAIL exception in state " + state, t);
                next(99);
            }
        });
    }

    private static void log(String msg) {
        LOGGER.info("[RMTEST] " + msg);
    }

    private void next(int newState) {
        state = newState;
        ticks = 0;
        step = 0;
        stateStart = System.currentTimeMillis();
    }

    private void fail(String msg) {
        LOGGER.error("[RMTEST] FAIL " + msg);
        next(99);
    }

    private void tick() throws Exception {
        Minecraft mc = Minecraft.getInstance();
        // Wall-clock based (50 ms units): client ticks stop while a replay is paused
        ticks = (int) ((System.currentTimeMillis() - stateStart) / 50);
        switch (state) {
            case 0 -> { // wait for title screen
                if (mc.screen instanceof TitleScreen && ticks > 40) {
                    // Force all mixin targets to load so every injector gets applied (and counted)
                    log("mixin audit start");
                    org.spongepowered.asm.mixin.MixinEnvironment.getCurrentEnvironment().audit();
                    log("mixin audit done");
                    if (Boolean.getBoolean("rmtest.auditOnly")) {
                        next(99);
                        return;
                    }
                    boolean found = false;
                    for (GuiEventListener child : mc.screen.children()) {
                        String name = child.getClass().getName();
                        if (name.contains("replaymod")) {
                            found = true;
                            log("title screen button: " + name);
                        }
                    }
                    log(found ? "OK replay viewer button present in main menu" : "FAIL no replay viewer button in main menu");
                    log("ReplayMod loaded: " + (ReplayMod.instance != null) + ", version " + ReplayMod.instance.getVersion()
                            + ", minimalMode " + ReplayMod.isMinimalMode());
                    // Skip the interactive "rename replay" popup so the file is moved to the replay folder right away
                    ReplayMod.instance.getSettingsRegistry().set(com.replaymod.recording.Setting.RENAME_DIALOG, false);
                    GameRules rules =new GameRules(WorldDataConfiguration.DEFAULT.enabledFeatures());
                    LevelSettings settings = new LevelSettings("rmtest", GameType.CREATIVE, false, Difficulty.PEACEFUL,
                            true, rules, WorldDataConfiguration.DEFAULT);
                    log("player type id before world: " + playerTypeId());
                    String replayOnly = System.getProperty("rmtest.replay", "");
                    if (!replayOnly.isEmpty()) {
                        replay = Path.of(replayOnly);
                        log("opening existing replay " + replay.getFileName());
                        next(4);
                        return;
                    }
                    String existing = System.getProperty("rmtest.world", "");
                    if (!existing.isEmpty()) {
                        log("opening existing world " + existing);
                        mc.createWorldOpenFlows().openWorld(existing, () -> fail("opening world cancelled"));
                    } else {
                        log("creating world");
                        mc.createWorldOpenFlows().createFreshLevel("rmtest-" + System.currentTimeMillis(), settings,
                                WorldOptions.defaultWithRandomSeed(), WorldPresets::createNormalWorldDimensions, mc.screen);
                    }
                    next(1);
                } else if (ticks > 20 * 120) {
                    fail("title screen never appeared");
                }
            }
            case 1 -> { // wait for player
                if (mc.player != null && mc.level != null && mc.screen == null) {
                    log("joined world, player type id while recording: " + playerTypeId());
                    next(20);
                } else if (ticks > 20 * 180) {
                    fail("never joined the world");
                }
            }
            case 20 -> { // hotkey that hides / shows the recording indicator
                var settings = ReplayMod.instance.getSettingsRegistry();
                var indicator = com.replaymod.recording.Setting.INDICATOR;
                if (ticks >= 20 && step == 0) {
                    step = 1;
                    net.minecraft.client.KeyMapping mapping = null;
                    for (net.minecraft.client.KeyMapping m : mc.options.keyMappings) {
                        if (m.getName().equals("key.replaymod.toggleindicator")) mapping = m;
                    }
                    if (mapping == null) {
                        fail("no toggle indicator key binding in the controls");
                        return;
                    }
                    log("OK toggle indicator key binding present, default key: " + mapping.getDefaultKey().getName()
                            + ", category " + mapping.getCategory());
                    // Bind it like a user would in the controls screen
                    mapping.setKey(com.mojang.blaze3d.platform.InputConstants.Type.KEYSYM.getOrCreate(org.lwjgl.glfw.GLFW.GLFW_KEY_F4));
                    net.minecraft.client.KeyMapping.resetMapping();
                    log("indicator setting before: " + settings.get(indicator));
                    pressKey(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_F4);
                } else if (ticks >= 40 && step == 1) {
                    step = 2;
                    boolean shown = settings.get(indicator);
                    log((!shown ? "OK" : "FAIL") + " pressing the key hid the indicator; recording stopped: "
                            + recordingStopped() + ", action bar: " + overlayMessage(mc));
                    pressKey(mc, org.lwjgl.glfw.GLFW.GLFW_KEY_F4);
                } else if (ticks >= 60 && step == 2) {
                    boolean shown = settings.get(indicator);
                    log((shown ? "OK" : "FAIL") + " pressing it again showed the indicator; recording stopped: "
                            + recordingStopped() + ", action bar: " + overlayMessage(mc));
                    next(10);
                }
            }
            case 10 -> { // recording indicator + pause menu recording controls
                if (ticks >= 40 && step == 0) {
                    step = 1;
                    log((hudRenders > 0 ? "OK" : "FAIL") + " HUD callback (recording indicator) rendered " + hudRenders + " times");
                    mc.setScreen(new net.minecraft.client.gui.screens.PauseScreen(true));
                    screenOverlayRenders = 0;
                } else if (ticks >= 80 && step == 1) {
                    step = 2;
                    log((screenOverlayRenders > 0 ? "OK" : "FAIL") + " jGui overlay on pause menu rendered "
                            + screenOverlayRenders + " times");
                    log("recording stopped before click: " + recordingStopped());
                    // Click "Stop recording" through the real input path: GLFW callback -> MouseHandler -> Forge hook
                    var disconnect = com.replaymod.core.versions.MCVer.findButton(mc.screen.children(), "menu.returnToMenu", 1)
                            .or(() -> com.replaymod.core.versions.MCVer.findButton(mc.screen.children(), "menu.disconnect", 1));
                    int y0 = disconnect.map(b -> b.getY()).orElse(-1);
                    if (y0 < 0) {
                        fail("no disconnect button on pause screen");
                        return;
                    }
                    int guiX = mc.getWindow().getGuiScaledWidth() / 2 - 100 + 102 + 49; // centre of the right (start/stop) button
                    int guiY = y0 + 24 + 10;
                    clickAt(mc, guiX, guiY);
                } else if (ticks >= 120 && step == 2) {
                    step = 3;
                    boolean stopped = recordingStopped();
                    log((stopped ? "OK" : "FAIL") + " clicking 'Stop recording' in the pause menu stopped the recording");
                    // Click again ("Start recording") so the rest of the test records normally
                    int y0 = com.replaymod.core.versions.MCVer.findButton(mc.screen.children(), "menu.returnToMenu", 1)
                            .or(() -> com.replaymod.core.versions.MCVer.findButton(mc.screen.children(), "menu.disconnect", 1))
                            .map(b -> b.getY()).orElse(0);
                    clickAt(mc, mc.getWindow().getGuiScaledWidth() / 2 - 100 + 102 + 49, y0 + 24 + 10);
                } else if (ticks >= 160 && step == 3) {
                    boolean stopped = recordingStopped();
                    log((!stopped ? "OK" : "FAIL") + " clicking 'Start recording' resumed the recording");
                    mc.setScreen(null);
                    next(2);
                }
            }
            case 2 -> { // play for 20s: look around and move
                mc.player.setYRot(mc.player.getYRot() + 3);
                mc.player.setDeltaMovement(0.2, 0.05, 0.1);
                if (ticks >= 100 && step == 0) {
                    step = 1;
                    mc.player.connection.sendCommand("time set noon");
                }
                if (ticks > 20 * 20) {
                    log("leaving world");
                    next(3); // before disconnecting: disconnect renders frames itself, which re-enters this handler
                    // Same as the pause menu's "Save and Quit to Title" button
                    mc.disconnectFromWorld(net.minecraft.network.chat.Component.literal("rmtest"));
                }
            }
            case 3 -> { // wait for the replay file
                if (System.currentTimeMillis() - lastPoll > 1000) {
                    lastPoll = System.currentTimeMillis();
                    Path folder = ReplayMod.instance.folders.getReplayFolder();
                    try (Stream<Path> files = Files.list(folder)) {
                        Optional<Path> newest = files.filter(p -> p.toString().endsWith(".mcpr"))
                                .filter(p -> p.toFile().lastModified() > RUN_START)
                                .max(Comparator.comparingLong(p -> p.toFile().length()));
                        // wait until the file stopped changing (post-processing writes it after the title screen shows)
                        long size = newest.map(p -> p.toFile().length()).orElse(-1L);
                        boolean stable = newest.isPresent() && size == lastSize;
                        lastSize = size;
                        if (stable && mc.screen instanceof TitleScreen && ticks > 20 * 8) {
                            replay = newest.get();
                            log("OK recorded replay " + replay.getFileName() + " (" + Files.size(replay) + " bytes)");
                            next(4);
                            return;
                        }
                    }
                }
                if (ticks > 20 * 120) {
                    fail("no .mcpr file appeared in " + ReplayMod.instance.folders.getReplayFolder());
                }
            }
            case 4 -> { // open the replay
                if (ticks > 40) {
                    ReplayFile file = ReplayMod.instance.files.open(replay);
                    log("replay metadata: duration " + file.getMetaData().getDuration() + " ms, mc "
                            + file.getMetaData().getMcVersion() + ", protocol " + file.getMetaData().getProtocolVersion());
                    ReplayModReplay.instance.startReplay(file, false, true);
                    log("replay started");
                    next(5);
                }
            }
            case 5 -> { // in replay
                ReplayHandler handler = ReplayModReplay.instance.getReplayHandler();
                if (handler == null) {
                    fail("replay handler vanished");
                    return;
                }
                ReplaySender sender = handler.getReplaySender();
                if (mc.screen instanceof net.minecraft.client.gui.screens.DisconnectedScreen) {
                    fail("replay was disconnected (network protocol error?)");
                    return;
                }
                if (ticks >= 100 && step == 0) {
                    step = 1;
                    log("in replay: level " + (mc.level != null) + ", camera " + handler.getCameraEntity()
                            + ", time " + sender.currentTimeStamp() + " ms, entities " + countEntities(mc)
                            + ", player type id " + playerTypeId() + ", recorded players " + countPlayers(mc));
                    jumpTarget = Math.min(15000, handler.getReplayDuration() - 1000);
                    log("jumping to " + jumpTarget + " ms");
                    sender.jumpToTime(jumpTarget);
                }
                if (ticks >= 300 && step == 1) {
                    step = 2;
                    int time = sender.currentTimeStamp();
                    log("after jump: time " + time + " ms, camera " + handler.getCameraEntity() + ", entities " + countEntities(mc)
                            + ", player type id " + playerTypeId() + ", recorded players " + countPlayers(mc));
                    log(time >= jumpTarget - 1000 ? "OK replay playback and seeking work" : "FAIL replay time did not reach the jump target");
                    handler.endReplay();
                    log("replay closed");
                    next(6);
                }
            }
            case 6 -> {
                if (ticks > 60) {
                    log("player type id after replay: " + playerTypeId());
                    log("DONE screen after replay: " + (mc.screen == null ? null : mc.screen.getClass().getName()));
                    mc.stop();
                    next(100);
                }
            }
            case 99 -> {
                if (ticks > 40) {
                    log("DONE (with failure)");
                    mc.stop();
                    next(100);
                }
            }
            default -> {}
        }
    }

    private static boolean recordingStopped() throws Exception {
        Object handler = com.replaymod.recording.ReplayModRecording.instance.getConnectionEventHandler();
        java.lang.reflect.Field f = handler.getClass().getDeclaredField("guiControls");
        f.setAccessible(true);
        Object controls = f.get(handler);
        if (controls == null) throw new IllegalStateException("not recording");
        return ((com.replaymod.recording.gui.GuiRecordingControls) controls).isStopped();
    }

    /** Simulates a real left click at the given GUI coordinates by invoking the game's own GLFW callbacks. */
    private static void clickAt(Minecraft mc, int guiX, int guiY) {
        long window = mc.getWindow().handle();
        double scale = mc.getWindow().getGuiScale();
        double px = guiX * scale, py = guiY * scale;
        log("clicking at gui " + guiX + "," + guiY + " (window " + px + "," + py + ")");
        org.lwjgl.glfw.GLFWCursorPosCallback pos = org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window, null);
        org.lwjgl.glfw.GLFW.glfwSetCursorPosCallback(window, pos);
        pos.invoke(window, px, py);
        org.lwjgl.glfw.GLFWMouseButtonCallback button = org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback(window, null);
        org.lwjgl.glfw.GLFW.glfwSetMouseButtonCallback(window, button);
        button.invoke(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
        button.invoke(window, org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
    }

    /** Simulates a real key press through the game's own GLFW key callback. */
    private static void pressKey(Minecraft mc, int key) {
        long window = mc.getWindow().handle();
        log("pressing key " + key);
        org.lwjgl.glfw.GLFWKeyCallback cb = org.lwjgl.glfw.GLFW.glfwSetKeyCallback(window, null);
        org.lwjgl.glfw.GLFW.glfwSetKeyCallback(window, cb);
        int scancode = org.lwjgl.glfw.GLFW.glfwGetKeyScancode(key);
        cb.invoke(window, key, scancode, org.lwjgl.glfw.GLFW.GLFW_PRESS, 0);
        cb.invoke(window, key, scancode, org.lwjgl.glfw.GLFW.GLFW_RELEASE, 0);
    }

    private static String overlayMessage(Minecraft mc) {
        try {
            for (java.lang.reflect.Field f : net.minecraft.client.gui.Gui.class.getDeclaredFields()) {
                if (f.getType() == net.minecraft.network.chat.Component.class && f.getName().equals("overlayMessageString")) {
                    f.setAccessible(true);
                    Object v = f.get(mc.gui);
                    return v == null ? null : ((net.minecraft.network.chat.Component) v).getString();
                }
            }
        } catch (Exception ignored) {}
        return "?";
    }

    private static int countEntities(Minecraft mc) {
        if (mc.level == null) return -1;
        int n = 0;
        for (Entity ignored : mc.level.entitiesForRendering()) n++;
        return n;
    }

    private static int playerTypeId() {
        return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getId(net.minecraft.world.entity.EntityType.PLAYER);
    }

    private static int countPlayers(Minecraft mc) {
        if (mc.level == null) return -1;
        int n = 0;
        for (Entity e : mc.level.entitiesForRendering()) if (e.getType() == net.minecraft.world.entity.EntityType.PLAYER) n++;
        return n;
    }
}
