# OxyDroid

Oxygen Not Included on Android, running natively with your own game files.

**Status: early skeleton. Nothing runs yet.**

OxyDroid is an open-source launcher. It contains no game files: you import your own copy of the
Linux version of Oxygen Not Included (Steam). The launcher starts it on Unity's Android ARM64 player
with an ARM64 Mono runtime, so the game's own C# code runs unchanged.

The code base starts as a copy of [ValDroid](https://github.com/udarmolota/ValDroid) (Valheim launcher) and still
carries Valheim-specific parts that are being replaced.

## What is adapted so far

- Project, package and application id renamed to `com.oxydroid`.
- `GameDescriptor.ONI`: executable, data folder, save folder and Steam app id for Oxygen Not Included.
- Unity player line moved to **6000.3.17f1**: package URL, `patch_libunity_target.py` addresses,
  Mono commit `dc7ab1aa0dd66ca15d4ffc9776e058993d53dee5`.

## Not done yet

- Steam stub for ONI's Steamworks.NET 20.1.0 (including Steam Input for gamepads).
- FMOD Studio 2.03.12 for Android.
- The simulation library (`libSimDLL.so`), which exists only for x86-64.
- Legacy-input touch and mouse layer.
- Removal of Valheim-only features (Thunderstore, crossplay, PlayFab Party, BepInEx bootstrap).

## License

See `LICENSE`, `NOTICE` and `LICENSES/`.
