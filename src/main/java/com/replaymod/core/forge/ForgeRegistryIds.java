package com.replaymod.core.forge;

import com.replaymod.replaystudio.lib.guava.base.Optional;
import com.replaymod.replaystudio.replay.ReplayFile;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.Identifier;
import net.minecraftforge.registries.ForgeRegistry;
import net.minecraftforge.registries.GameData;
import net.minecraftforge.registries.RegistryManager;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Forge port: Forge can change the numeric ids of built-in registries (entity types, blocks, items, ...) while in a
 * world, e.g. a singleplayer world restores the ids saved in its level.dat and a Forge server syncs its own ids.
 * Recorded packets use these ids, but by the time a replay is played back Forge has reverted to its default ids, so
 * entities would get the wrong type (and the replay is disconnected with "Network Protocol Error").
 * We therefore store the ids in use while recording in the replay file and inject them while it is played back.
 */
public final class ForgeRegistryIds {
    public static final String ENTRY = "forge_registry_ids.nbt";

    private ForgeRegistryIds() {}

    /** Takes a snapshot of the ids currently in use (same set Forge syncs to clients). Call on the main thread. */
    public static CompoundTag capture() {
        CompoundTag root = new CompoundTag();
        RegistryManager.ACTIVE.takeSnapshot(false).forEach((name, snapshot) -> root.put(name.toString(), snapshot.write()));
        return root;
    }

    public static void write(ReplayFile replayFile, CompoundTag ids) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.writeCompressed(ids, bytes);
        try (OutputStream out = replayFile.write(ENTRY)) {
            out.write(bytes.toByteArray());
        }
    }

    /** Reads the ids stored in the replay, or returns null if it has none (older replays). */
    public static Map<Identifier, ForgeRegistry.Snapshot> read(ReplayFile replayFile) throws IOException {
        Optional<InputStream> in = replayFile.get(ENTRY);
        if (!in.isPresent()) {
            return null;
        }
        byte[] bytes;
        try (InputStream stream = in.get()) {
            bytes = stream.readAllBytes();
        }
        CompoundTag root = NbtIo.readCompressed(new ByteArrayInputStream(bytes), NbtAccounter.unlimitedHeap());
        Map<Identifier, ForgeRegistry.Snapshot> snapshot = new HashMap<>();
        for (String key : root.keySet()) {
            snapshot.put(Identifier.parse(key), ForgeRegistry.Snapshot.read(root.getCompoundOrEmpty(key)));
        }
        return snapshot;
    }

    /** Switches the game to the given ids, the same way Forge applies ids received from a server. */
    public static void inject(Map<Identifier, ForgeRegistry.Snapshot> snapshot) {
        GameData.injectSnapshot(snapshot, false, false);
    }

    public static void revert() {
        GameData.revertToFrozen();
    }
}
