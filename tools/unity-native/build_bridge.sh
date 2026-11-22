#!/bin/sh
# Build OxyDroidBridge.dll (tools/unity-native/bridge) without the Unity Editor.
#
# The bridge compiles against the player's engine modules and Mono's framework profile only.
#
# usage: build_bridge.sh <unity-managed-dir> <out-dir>
#   <unity-managed-dir>  UnityEngine*.dll of the player (Variations/mono/Managed of the Android
#                        Support package, as extracted by prepare_unity_module.sh)
#   <out-dir>            receives OxyDroidBridge.dll
# environment:
#   CSC        C# compiler command: "csc" (Mono, Linux CI) or "<dotnet> <csc.dll>" (the Roslyn that
#              ships with the Unity Editor: Editor/Data/NetCoreRuntime/dotnet + DotNetSdkRoslyn/csc.dll)
#   MONO_API   Mono's framework assemblies to compile against: tools/unity-native/monoref/4.5 (the
#              4.5 profile of Unity's Mono, MIT, as in the Editor's Data/MonoBleedingEdge/lib/mono/4.5)
set -e
USAGE="usage: build_bridge.sh <unity-managed-dir> <out-dir>"
UNITY=${1:?$USAGE}
OUT=${2:?$USAGE}
: "${CSC:?set CSC to the C# compiler command}"
: "${MONO_API:?set MONO_API to Mono's framework assemblies (tools/unity-native/monoref/4.5)}"
HERE=$(cd "$(dirname "$0")" && pwd)
WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

# Reference assemblies: Mono's framework profile (what the player's Mono runs) and the engine modules.
REFS="$WORK/refs.rsp"
: > "$REFS"
for f in "$MONO_API/mscorlib.dll" "$MONO_API/System.dll" "$MONO_API/System.Core.dll" "$MONO_API"/Facades/*.dll \
         "$UNITY"/UnityEngine*.dll; do
    printf '%s\n' "-r:\"$f\"" >> "$REFS"
done

COMMON="-nologo -noconfig -nostdlib+ -target:library -langversion:9.0 -unsafe+ -optimize+ -deterministic -warn:0"

mkdir -p "$OUT"
# shellcheck disable=SC2086
$CSC $COMMON -out:"$OUT/OxyDroidBridge.dll" @"$REFS" "$HERE"/bridge/*.cs
echo "built $OUT/OxyDroidBridge.dll"
