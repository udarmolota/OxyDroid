# OxyDroid

**OxyDroid** brings **Oxygen Not Included to mobile**: play the full PC game on Android, natively, with
your own copy from Steam.

OxyDroid is an unofficial, open-source launcher. It contains no game files. You bring your own copy
of Oxygen Not Included, and the launcher runs it on Unity's own ARM64 Android player with the game's
C# code on an ARM64 Mono runtime. The game itself is not emulated.

> OxyDroid is not affiliated with or endorsed by Klei Entertainment. Oxygen Not Included is a
> trademark of Klei Entertainment. You need to own the game.

## Status

Early, and playable. The game starts, creates and loads colonies, saves, and can be played with
touch alone.

So far it has been run on **one phone**: a Galaxy S25 Ultra (Snapdragon 8 Elite, Adreno 830, 12 GB).
On it a cycle-170 colony runs at 50 to 70 fps at double speed, using about 1.8 GB of memory once
loaded.

Not tested yet:

- **Mali and other GPUs.** The texture path for them is in place (see below) and has been checked on
  the Adreno phone by hiding its BC formats from the engine, but no Mali phone has run the game.
- **Phones with less than 12 GB.** Loading a colony peaks near 3 GB.
- **Late game.** Nothing beyond about cycle 170 has been measured.
- **Some of the input and Steam features:** pinch zoom, typing with the on-screen keyboard and
  sending achievements to Steam are written but not confirmed on a device.
- **Mods.** Not supported yet. The game's Workshop is not available without signing in, so mods
  will come through the same Steam sign-in as the game download.

## What works

- **The whole game**, including the simulation: the game's native simulation library exists for
  Linux as x86-64 only, so OxyDroid loads the ARM64 half of the macOS build's library in process.
- **Touch controls.** One finger uses the selected tool, two fingers drag the map, a pinch zooms, a
  long press is a right click. On-screen buttons, sticks and a touchpad are on top of that, with an
  editor for your own layout.
- **Getting the game.** Download it inside the launcher with your Steam account, or import a zip.
- **Saves.** Download your colonies from Steam Cloud, or import and export saves and settings as a
  zip. Sending saves back to Steam Cloud is not available yet.
- **Steam achievements** are kept on the phone, to be sent to your account.
- **Several instances** of the game side by side, each with its own saves and settings.

## Requirements

- Android 11 or newer, ARM64.
- **Minimum:** Snapdragon 865 / Dimensity 1000–1100 class.
- **Recommended:** Snapdragon 8 Gen 1 / Dimensity 8100 or newer.
- Anything below the minimum is experimental: it may start, and it is not a target.
- A GPU with Vulkan. Adreno is the tested one.
- About 8 GB of free storage per instance.
- Your own copy of Oxygen Not Included on Steam.

## Get it only from GitHub

Download OxyDroid only from the [Releases page](https://github.com/udarmolota/OxyDroid/releases).
Copies from other sites may be changed, and OxyDroid asks for your **Steam login** (to download the
game, for Steam Cloud and for achievements). The real app uses it only to sign in to Steam and never
stores it. Repacked launchers are not supported.

Releases are signed with my own key. Certificate fingerprint (SHA-256):

```
F8:5A:43:7A:50:90:FB:84:B9:EB:2A:4C:BF:F5:AB:C0:7E:4D:3B:A2:D5:C4:BE:02:59:63:6F:3A:62:4E:D2:C6
```

You can check it in apps like MT Manager or App Manager. The launcher checks it too, and says so when
a copy is signed with another key. The real OxyDroid also installs over itself as an update: if
Android refuses to update your copy with the one from GitHub, your copy is not the original.

## Getting the game files

**From Steam, inside the launcher.** Sign in with your Steam account on the download screen. The
launcher fetches the Linux build of the game and the one file it needs from the macOS build. The
sign-in is approved in the Steam Mobile app, so Steam Guard must be turned on there; Steam Guard
codes are not supported. Your password goes to Steam only and is never stored.

**From a zip.** Pack the Linux build of the game, and add the macOS build's simulation library as
`macos/SimDLL` (in the macOS build it is
`OxygenNotIncluded.app/Contents/PlugIns/SimDLL.bundle/Contents/MacOS/SimDLL`). Both builds must be
the same game version.

## Textures on GPUs without BC formats

The game ships its art compressed for PC GPUs (DXT1, DXT5, BC7), about 1.5 GB of it. Mali and
PowerVR GPUs cannot sample those formats, and Unity would unpack every texture to four to eight
times its size.

On such a GPU OxyDroid converts the textures once to ASTC, in place, before the first launch. It
takes under a minute on a flagship phone and is done only once per instance. The conversion is one
way: the instance keeps its ASTC textures.

## How it works

- **Player:** Unity's ARM64 Android player (6000.3.17f1), patched to accept data built for another
  platform.
- **Scripting:** `il2mono`, a replacement for the player's IL2CPP library that runs the game's
  assemblies on ARM64 Mono.
- **Simulation:** an in-process Mach-O loader for the macOS `SimDLL`, with the Apple ABI and C++
  library differences bridged.
- **Steam:** a generated ARM64 stand-in for `libsteam_api.so`; the game runs offline.
- **Audio:** FMOD Studio for Android.
- **Input:** the overlay turns touches into mouse and keyboard events for the player.

The code base started as a copy of [ValDroid](https://github.com/udarmolota/ValDroid) and still
carries parts of it that this game does not use, the box64 emulation path among them. They are
hidden in the app and will be removed.

## Building

The native player module needs files from Unity's Android Support package, which are not in this
repository:

1. Run `tools/unity-native/prepare_unity_module.sh` with the Unity 6000.3.17f1 Android Support
   package. The script's header says where the package comes from and what it produces.
2. Put `nativeEngine=true` in `local.properties`.
3. Build with `./gradlew :app:assembleDebug`.

## License

GPL-3.0 with an additional permission; see `LICENSE`, `NOTICE` and `LICENSES/`. Third-party
components are listed in `NOTICE` with their own licenses.
