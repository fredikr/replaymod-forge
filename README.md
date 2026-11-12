# ReplayMod for Forge 1.21.11 (unofficial port)

[Русская версия](README.ru.md)

An unofficial port of [ReplayMod](https://github.com/ReplayMod/ReplayMod) 2.6.28 (Fabric only upstream) to
**Minecraft Forge 1.21.11**, with a few additions of our own.

> This is **not** an official build. The ReplayMod authors (CrushedPixel, johni0702) did not make it and do not
> support it. Please report problems with this port [here](https://github.com/fredikr/replaymod-forge/issues),
> not upstream.

## Install

1. Minecraft 1.21.11, Forge 61.x (tested with 61.1.5 and 61.2.4), Java 21.
2. Put `replaymod-1.21.11-2.6.28-forge.jar` into the `mods` folder of your Forge profile.
   Remove the Fabric build of ReplayMod from that folder if it is there.
3. Client only, nothing to install on servers. All libraries are bundled in the jar.

Works together with OptiFine (tested against OptiFine 1.21.11 HD U J9 pre4).

## Usage

Recording starts automatically when you join a world or a server. The pause menu (Esc) has *Pause* and
*Stop recording* / *Start recording* buttons. The replay is saved when you leave; open it with the
**Replay Viewer** button in the main menu. Default keys in the viewer:

| Key | Action |
|---|---|
| WASD, Space, Shift | Fly the camera |
| Mouse wheel | Camera speed |
| Left/right click an entity | Spectate it (Shift to leave) |
| P | Play / pause |
| T | Show the mouse cursor for the replay GUI (F1 then hides the GUI) |
| M | Add event marker |
| J / L / K | Roll camera left / right / reset |
| Z | Full brightness |
| B | Player overview |
| Q | Quick mode |
| I / O | Position / time keyframe (camera paths) |
| H | Show camera path |
| C / X | Clear keyframes / keyframe presets |
| V | Sync timeline |
| Ctrl+Z, Ctrl+Y | Undo / redo path changes |

All keys can be changed under *Options → Controls → Replay Mod*.

## Additions in this port

- **Toggle the recording indicator** – a new key binding *Show/hide recording indicator* (unbound by default)
  hides the "REC" label in the top left corner while recording keeps running. The choice is remembered.

## Forge-specific changes

- **Screen hooks via Forge events.** Forge (and OptiFine, which replaces `GameRenderer` and `KeyboardHandler`
  and calls Forge's hooks via reflection) leave no vanilla call sites for jGui's screen render / keyboard mixins,
  so these use `ScreenEvent.Render.Post` and `ScreenEvent.Key*/CharacterTyped.Pre` instead. The HUD indicator
  is drawn from a Forge GUI layer.
- **Registry ids are stored in replays.** Forge can renumber built-in registries while in a world (old
  singleplayer worlds restore ids saved in `level.dat`, Forge servers sync theirs). Recorded packets use those
  ids, but playback happens with Forge's default ids, which turned players into minecarts and disconnected the
  replay with *Network Protocol Error*. The ids in use are now saved as `forge_registry_ids.nbt` in the replay
  and injected during playback (`ForgeRegistryIds`).
- **ViaVersion mappings are awaited.** ReplayStudio builds its packet tables right after starting the bundled
  ViaVersion, which loads mapping data asynchronously. Opening a replay right after starting the game could fail
  (`Id 31 in 1.14 (477) ... already assigned to KeepAlive`). Loading now starts at mod init and is awaited
  (`ViaMappings`).
- No Iris integration (not on Forge), so ODS (stereo 360°) rendering is disabled; all other render modes are
  unaffected. No ModMenu integration. The `replaymod:restrict` channel is not registered (same end result as
  upstream: replays from servers that sent restrictions won't open).

## Not tested yet

- Rendering to video (needs [FFmpeg](https://ffmpeg.org/) on `PATH` or in `.minecraft/ffmpeg/bin`).
- Camera path editor, Blender export, YouTube upload.
- Compatibility with other rendering mods (Embeddium, Oculus, ...).
- With OptiFine shaders, the first-person hand of the spectated player may not render (OptiFine J9 lacks the
  method ReplayMod hooks into).

## Building

Requires JDK 21.

```bash
./gradlew build
```

The mod jar is `build/libs/replaymod-1.21.11-2.6.28-forge.jar` (the `-slim` jar is without bundled libraries).

`libs/ReplayStudio.jar` is ReplayStudio built from the `libs/ReplayStudio` folder of ReplayMod commit
[`13ca343`](https://github.com/ReplayMod/ReplayMod/commit/13ca34329be20fd3f3d2b2ccb4512c7d3f028871)
(Release 2.6.28).

### In-game test

`ingame-test/` is a small Forge mod that drives the real game: it creates (or opens) a world, checks the
recording indicator and the pause menu buttons with real mouse and keyboard input, records, opens the replay,
seeks, and closes it. It logs `[RMTEST] OK ...` / `FAIL ...` lines.

```bash
./gradlew build
mkdir -p ingame-test/run/mods
cp build/libs/replaymod-1.21.11-2.6.28-forge.jar ingame-test/run/mods/
cd ingame-test
./gradlew runClient
```

Options: `-Drmtest.world=<save folder>` records in an existing world from `ingame-test/run/saves`,
`-Drmtest.replay=<path to .mcpr>` only opens the given replay.

### Mixin check against OptiFine

`tools/OfBuild.java` rebuilds OptiFine's patched classes from an OptiFine jar and the vanilla client jar,
`tools/MixinCheck.java` checks every mixin injection point of the mod against a set of class jars
(earlier jars win, so put the OptiFine classes first). Both need ASM 9.9 on the classpath
(`MixinCheck`) or the OptiFine jar (`OfBuild`, it uses OptiFine's own xdelta patcher).

```bash
java -cp OptiFine.jar tools/OfBuild.java OptiFine.jar minecraft-1.21.11-client.jar of-patched.jar
java -cp "asm.jar;asm-tree.jar" tools/MixinCheck.java replaymod-forge.jar of-patched.jar forge-client.jar forge-universal.jar
```

## License

GPL-3.0-or-later, same as ReplayMod (see [LICENSE](LICENSE)). Original work © the ReplayMod authors.
