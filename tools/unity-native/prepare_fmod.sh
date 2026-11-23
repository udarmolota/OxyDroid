#!/bin/sh
# FMOD Studio for the native engine: Oxygen Not Included's audio runs on FMOD 2.03.12
# (libfmodstudio.so in its Plugins folder is the x86-64 Linux build). The Android ARM64 build of the
# same version comes from the FMOD Engine package for Android (fmod.com, needs an FMOD account and is
# under FMOD's licence). The two libraries are committed in unity/src/main/jniLibs/arm64-v8a, as the
# other launchers keep theirs; this script only replaces them, for another FMOD version.
#
# usage: prepare_fmod.sh <FMOD Engine Android folder>
#   The folder is the unpacked "FMOD Engine" Android package (fmodstudioapi20312android), holding
#   api/core/lib/arm64-v8a/libfmod.so and api/studio/lib/arm64-v8a/libfmodstudio.so.
#
# Result (committed, picked up by Gradle from the module's source set):
#   unity/src/main/jniLibs/arm64-v8a/libfmod.so
#   unity/src/main/jniLibs/arm64-v8a/libfmodstudio.so
# Their Java side (org.fmod.*) is app/libs/fmod.jar; NativeUnityActivity.loadFmod loads all three.
set -e
SRC=${1:?usage: prepare_fmod.sh <FMOD Engine Android folder>}
HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(cd "$HERE/../.." && pwd)
OUT=$ROOT/unity/src/main/jniLibs/arm64-v8a
CORE=$SRC/api/core/lib/arm64-v8a/libfmod.so
STUDIO=$SRC/api/studio/lib/arm64-v8a/libfmodstudio.so
for f in "$CORE" "$STUDIO"; do
    [ -f "$f" ] || { echo "missing $f" >&2; exit 1; }
done
mkdir -p "$OUT"
cp "$CORE" "$STUDIO" "$OUT/"
echo "prepared FMOD in $OUT"
