package com.oxydroid.game;

/**
 * Immutable description of the Linux game layout understood by the launcher.
 *
 * Keep game-specific paths here instead of scattering them through import,
 * validation, and launch code.  The first OxyDroid milestone supports one game,
 * and it is Oxygen Not Included (Steam Linux depot 457143).
 */
public final class GameDescriptor {
    public static final GameDescriptor ONI = new GameDescriptor(
            "OxygenNotIncluded",
            "OxygenNotIncluded_Data",
            "unity3d/Klei/Oxygen Not Included",
            "457140",
            new String[] {
                    "OxygenNotIncluded",
                    "UnityPlayer.so",
                    "OxygenNotIncluded_Data/globalgamemanagers",
                    "OxygenNotIncluded_Data/Managed/Assembly-CSharp.dll",
                    "OxygenNotIncluded_Data/Managed/Assembly-CSharp-firstpass.dll",
                    "OxygenNotIncluded_Data/Managed/mscorlib.dll",
                    "OxygenNotIncluded_Data/MonoBleedingEdge/x86_64/libmonobdwgc-2.0.so",
                    "OxygenNotIncluded_Data/MonoBleedingEdge/x86_64/libmono-native.so",
                    "OxygenNotIncluded_Data/MonoBleedingEdge/x86_64/libMonoPosixHelper.so",
                    "OxygenNotIncluded_Data/Plugins/x86_64/libSimDLL.so"
            });

    private final String executable;
    private final String dataDirectory;
    private final String userDataDirectory;
    private final String steamAppId;
    private final String[] requiredFiles;

    private GameDescriptor(String executable,
                           String dataDirectory,
                           String userDataDirectory,
                           String steamAppId,
                           String[] requiredFiles) {
        this.executable = executable;
        this.dataDirectory = dataDirectory;
        this.userDataDirectory = userDataDirectory;
        this.steamAppId = steamAppId;
        this.requiredFiles = requiredFiles.clone();
    }

    public String executable() {
        return executable;
    }

    public String dataDirectory() {
        return dataDirectory;
    }

    public String userDataDirectory() {
        return userDataDirectory;
    }

    public String steamAppId() {
        return steamAppId;
    }

    public String[] requiredFiles() {
        return requiredFiles.clone();
    }
}
