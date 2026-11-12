package com.replaymod.core.forge;

import com.replaymod.replaystudio.lib.viaversion.api.Via;
import com.replaymod.replaystudio.lib.viaversion.protocol.ProtocolManagerImpl;
import com.replaymod.replaystudio.viaversion.CustomViaManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Forge port: ReplayStudio's PacketTypeRegistry builds its packet id tables in its static initializer, right after
 * starting ViaVersion. ViaVersion however loads the mapping data of many protocols asynchronously and only registers
 * their packets once that is done, so if the registry is built too early, its tables are wrong and the static
 * initializer fails ("Id 31 in 1.14 (477) to be assigned to OpenHorseWindow but already assigned to KeepAlive"),
 * which breaks opening replays until the game is restarted. This happened when a replay was opened right after
 * starting the game. We start ViaVersion early and wait for its mappings before the registry is first used.
 */
public final class ViaMappings {
    private static final Logger LOGGER = LogManager.getLogger("ReplayMod");
    private static final long TIMEOUT_MS = 60_000;
    private static volatile boolean ready;

    private ViaMappings() {}

    /** Starts loading in the background, so the first replay doesn't have to wait. */
    public static void preload() {
        Thread thread = new Thread(() -> {
            try {
                await();
                // Build the packet tables now as well
                com.replaymod.replaystudio.protocol.PacketTypeRegistry.get(
                        com.replaymod.replaystudio.lib.viaversion.api.protocol.version.ProtocolVersion.v1_8,
                        com.replaymod.replaystudio.lib.viaversion.api.protocol.packet.State.LOGIN);
            } catch (Throwable t) {
                LOGGER.error("Preloading ViaVersion mappings:", t);
            }
        }, "ReplayMod ViaVersion init");
        thread.setDaemon(true);
        thread.start();
    }

    /** Blocks until ViaVersion has finished loading its mapping data (or a timeout passed). */
    public static void await() {
        if (ready) {
            return;
        }
        synchronized (ViaMappings.class) {
            if (ready) {
                return;
            }
            CustomViaManager.initialize();
            ProtocolManagerImpl protocolManager = (ProtocolManagerImpl) Via.getManager().getProtocolManager();
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            // false: do not join, a protocol whose mappings failed to load would throw here
            while (!protocolManager.checkForMappingCompletion(false)) {
                if (System.currentTimeMillis() > deadline) {
                    LOGGER.warn("Timed out waiting for ViaVersion mapping data to load");
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            ready = true;
        }
    }
}
