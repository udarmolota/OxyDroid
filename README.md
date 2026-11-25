# OxyDroid

**OxyDroid** is an unofficial, open-source launcher that brings
**[Oxygen Not Included](https://www.klei.com/games/oxygen-not-included) to mobile**: the full PC game
on ARM64 Android phones, run natively on Unity's own Android player, with touch controls, Vulkan
graphics, sound, mods and DLC.

> [!NOTE]
> OxyDroid needs **your own copy of Oxygen Not Included**. Steam owners can download the game and the
> DLC they own right in the app; GOG owners can download the Linux build in the app too; you can also
> import your own game files as a ZIP.

> [!WARNING]
> **The app is in alpha.** It is tested on two phones so far. Back up your colonies.

## Features

- ✔️ **Native, not emulated** — Unity's own ARM64 Android player runs the game, and the game's C#
  code runs on an ARM64 build of Mono
- ✔️ **Native simulation** with the macOS build's `SimDLL`; a copy with the Linux build only (GOG or
  elsewhere) runs too, with the simulation emulated
- ✔️ **In-app downloads** — the game and the DLC you own from Steam, the game from GOG
- ✔️ **DLC** (tested with Spaced Out!) — download them, add a DLC ZIP to an instance, export an
  instance's DLC
- ✔️ **Steam Workshop** in the app — browse it, mark mods and download them, **no sign-in needed**;
  or download mods by link or id
- ✔️ **Mods screen** — the game's own list in load order: switch mods on and off, reorder, delete,
  and see the game build each mod is made for. New mods switch on by themselves
- ✔️ **Steam Cloud saves** — get your PC colonies onto the phone and send them back
- ✔️ **Steam achievements** — kept on the phone and synced with your account
- ✔️ **Touch controls** — one finger uses the tool, two fingers move the map, a pinch zooms, a long
  press is a right click
- ✔️ **Editable on-screen controls** — buttons, sticks and a touchpad, with an editor for your own
  layout; the eye icon hides them all if you'd rather tap the game's own UI
- ✔️ **Render resolution and frame rate limit** (~60 FPS by default, for less heat)
- ✔️ **ASTC texture conversion** for Mali and other GPUs without PC texture formats
- ✔️ **Multiple instances**, each with its own colonies, settings and controls
- ✔️ **Save / settings / controls / DLC import & export** as ZIPs
- ✔️ **In-app wiki** in English, Russian, Spanish and Portuguese
- ✔️ **On-screen keyboard** — name colonies and duplicants (Latin letters)
- ✔️ **Report a bug** — logs and device info in one ZIP

## Project status & what to expect

OxyDroid is **new** (first release in **November 2026**) and built by **one person**. It shares its
roots with [RimDroid](https://github.com/udarmolota/RimDroid) and
[Zomdroid](https://github.com/udarmolota/zomdroid), but Oxygen Not Included runs a different way:
nothing of the game is emulated, unless your copy has no macOS simulation file.

Tested so far:

| Phone | Chip / GPU | RAM | Result |
|---|---|---|---|
| Galaxy S25 Ultra | Snapdragon 8 Elite / Adreno 830 | 12 GB | a cycle-170 colony at 50–70 FPS at double speed; 60 FPS with the emulated simulation, with drops to 35–48 once the phone is hot |
| TECNO LJ7 | Dimensity 7300 / Mali-G615 | 8 GB | 20–26 FPS for an hour of play, no crash |

Oxygen Not Included is **CPU-heavy**: the game's main thread is the limit on most phones, not the GPU.
A bigger, older colony asks more of the phone. Lowering the render resolution helps the GPU, but it
does not help much when the CPU is the limit.

## System requirements

- Android 11 or newer, ARM64, a GPU with Vulkan
- ✅ **Minimum:** Snapdragon 865 / Dimensity 1000–1100 class
- ⭐ **Recommended:** Snapdragon 8 Gen 1 / Dimensity 8100 or newer
- 🧪 **Below the minimum:** experimental — it may start and play, but it is not a target
- **8 GB of RAM** or more; loading a colony peaks near 3 GB. Phones with less are not tested
- About 8 GB of free storage per instance
- Your own copy of Oxygen Not Included (Steam or GOG)

## Getting the best performance

- **Use the macOS simulation file if you can.** The Steam download brings it. Without it the
  simulation is emulated, which works the processor harder: more heat, and the frame rate drops as
  the phone gets hot.
- **Turn on your phone's game booster and choose its highest-performance profile** — Samsung *Game
  Booster*, Xiaomi/POCO *Game Turbo*, Realme/OPPO/OnePlus *Game Space*, vivo/iQOO *Ultra Game Mode*,
  Infinix/Tecno *Game Mode*. Make sure it is not "balanced" or a battery saver.
- **Keep the 60 FPS limit.** The game gains little above 60, and the phone stays cooler.
- **Close background apps** so the game gets the RAM and CPU to itself.

## Roadmap

- [x] Native Oxygen Not Included on ARM64 Android
- [x] Touch controls and an on-screen controls editor
- [x] Steam download, Steam Cloud saves, Steam achievements
- [x] Mali and other GPUs without PC texture formats
- [x] Copies without the macOS simulation file (emulated simulation)
- [x] DLC
- [x] Steam Workshop without sign-in
- [x] GOG download
- [ ] Less heat in long sessions
- [ ] Faster pinch zoom
- [ ] Typing in more languages with the on-screen keyboard

## Get it only from GitHub

Download OxyDroid only from the [Releases page](https://github.com/udarmolota/OxyDroid/releases).
Copies from other sites may be changed, and OxyDroid asks for your **Steam login** (to download the
game and DLC, for Steam Cloud and for achievements). The real app uses it only to sign in to Steam and
never stores it. Repacked launchers are not supported.

Releases are signed with my own key. Certificate fingerprint (SHA-256):

```
F8:5A:43:7A:50:90:FB:84:B9:EB:2A:4C:BF:F5:AB:C0:7E:4D:3B:A2:D5:C4:BE:02:59:63:6F:3A:62:4E:D2:C6
```

You can check it in apps like MT Manager or App Manager. The launcher checks it too, and says so when
a copy is signed with another key. The real OxyDroid also installs over itself as an update: if
Android refuses to update your copy with the one from GitHub, your copy is not the original.

## Getting the game files

**From Steam, inside the launcher.** Sign in with your Steam account on the **Game** page. The
launcher fetches the Linux build of the game and the one file it needs from the macOS build, and the
**DLC** tab fetches the DLC you own. The sign-in is approved in the Steam Mobile app, so Steam Guard
must be turned on there; Steam Guard codes are not supported. Your password goes to Steam only and is
never stored.

**From GOG, inside the launcher.** Side menu → **Download ONI**. GOG has the Linux build only, so the
simulation runs emulated, unless you add the macOS file later (instance settings → **Additional
files**).

**From a ZIP.** Pack the Linux build of the game and import it with **+**. Add the macOS build's
simulation library as `macos/SimDLL` (in the macOS build it is
`OxygenNotIncluded.app/Contents/PlugIns/SimDLL.bundle/Contents/MacOS/SimDLL`), or add it later in the
instance settings. Both builds must be the same game version. With your own Steam client on a PC, the
console (`steam://open/console`) downloads them: `download_depot 457140 457143` (Linux) and
`download_depot 457140 457142` (macOS).

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
  library differences bridged. Without the macOS file, the Linux `SimDLL` runs under
  [box64](https://github.com/ptitSeb/box64), embedded in process for that one library only.
- **Steam:** an ARM64 stand-in for `libsteam_api.so`; the game runs offline, with the DLC the
  instance has.
- **Mods:** a small bridge assembly switches newly installed mods on in the game.
- **Audio:** FMOD Studio for Android.
- **Input:** the overlay turns touches into mouse and keyboard events for the player.

The code base started as a copy of [ValDroid](https://github.com/udarmolota/ValDroid) and still
carries parts of it that this game does not use. They are hidden in the app and will be removed.

## Building

The native player module needs files from Unity's Android Support package, which are not in this
repository:

1. Run `tools/unity-native/prepare_unity_module.sh` with the Unity 6000.3.17f1 Android Support
   package. The script's header says where the package comes from and what it produces.
2. Put `nativeEngine=true` in `local.properties`.
3. Build with `./gradlew :app:assembleDebug`.

## Supporting development

This is an independent project. To help keep it going, contributions are welcome via
[Ko-Fi](https://ko-fi.com/udarmolota).

## Feedback

Please report issues or request features via
[GitHub Issues](https://github.com/udarmolota/OxyDroid/issues). Tap **Report a bug** in the app's
side menu right after the problem happens, and attach the ZIP so we can see what your device is doing.

Playing on a phone that is not in the table above, or a big late-game colony? **Tell us how it
runs** — every report makes the requirements more precise.

TG: [t.me/udarmolota](https://t.me/udarmolota) ·
Reddit: [r/UdarMolotaDev](https://www.reddit.com/r/UdarMolotaDev/)

## Credits & third-party sources

- [Mono (Unity fork)](https://github.com/Unity-Technologies/mono) — the ARM64 runtime for the game's
  code
- [box64](https://github.com/ptitSeb/box64) by ptitSeb — runs the Linux simulation file when the
  macOS one is missing
- [astcenc](https://github.com/ARM-software/astc-encoder) and
  [bcdec](https://github.com/iOrange/bcdec) — the texture conversion for Mali and other GPUs
- [JavaSteam](https://github.com/Longi94/JavaSteam) — Steam sign-in, downloads and Steam Cloud
- [Zomdroid](https://github.com/udarmolota/zomdroid) (MIT) — the on-screen controls, the controls
  editor and the GOG download are ported from it
- [RimDroid](https://github.com/udarmolota/RimDroid) — the in-app Steam Workshop and much of the
  launcher
- Full list with licenses: `NOTICE`

## License

GPL-3.0 with an additional permission; see `LICENSE`, `NOTICE` and `LICENSES/`. Third-party
components are listed in `NOTICE` with their own licenses.

> [!IMPORTANT]
> OxyDroid is not affiliated with or endorsed by Klei Entertainment. Oxygen Not Included is a
> trademark of Klei Entertainment. OxyDroid contains no game files; you need to own the game.
