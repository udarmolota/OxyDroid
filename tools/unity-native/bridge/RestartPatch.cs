using System;
using System.IO;
using System.Reflection;
using UnityEngine;

namespace OxyDroid
{
    // The game restarts itself after a change of mods (and of language or DLC): App.Restart starts the
    // desktop "Restarter" program next to the executable and quits. Android has neither, so the game
    // just closed. Here App.Restart is patched, through the game's own Harmony, to leave a note for the
    // launcher (OXYDROID_RESTART_FLAG, a file in the instance) and quit: the launcher, back on screen,
    // finds the note and starts the game again (LauncherFragment.maybeRestartGame).
    //
    // This assembly does not reference Harmony or the game, so both are reached by reflection. If any
    // step fails the game is left as it is, and the line in the log says why.
    internal static class RestartPatch
    {
        static string s_Flag;

        internal static void Install()
        {
            s_Flag = Environment.GetEnvironmentVariable("OXYDROID_RESTART_FLAG");
            if (string.IsNullOrEmpty(s_Flag))
                return;
            try
            {
                var app = Type.GetType("App, Assembly-CSharp-firstpass");
                var restart = app == null ? null : app.GetMethod("Restart", BindingFlags.Public | BindingFlags.Instance);
                var harmonyType = Type.GetType("HarmonyLib.Harmony, 0Harmony");
                var harmonyMethodType = Type.GetType("HarmonyLib.HarmonyMethod, 0Harmony");
                if (restart == null || harmonyType == null || harmonyMethodType == null)
                {
                    Log("not installed: " + (restart == null ? "App.Restart" : "Harmony") + " not found");
                    return;
                }
                var prefix = typeof(RestartPatch).GetMethod(nameof(Prefix), BindingFlags.NonPublic | BindingFlags.Static);
                object harmony = Activator.CreateInstance(harmonyType, "com.oxydroid.restart");
                object harmonyPrefix = Activator.CreateInstance(harmonyMethodType, prefix);
                // Harmony.Patch(MethodBase original, HarmonyMethod prefix, ...): the number of optional
                // HarmonyMethod parameters after the prefix differs between Harmony versions (four in the
                // game's 2.4), so the overload is found by its first two parameters.
                MethodInfo patch = null;
                foreach (var m in harmonyType.GetMethods(BindingFlags.Public | BindingFlags.Instance))
                {
                    var ps = m.GetParameters();
                    if (m.Name == "Patch" && ps.Length >= 2 && ps[0].ParameterType == typeof(MethodBase)
                        && ps[1].ParameterType == harmonyMethodType)
                    {
                        patch = m;
                        break;
                    }
                }
                if (patch == null)
                {
                    Log("not installed: Harmony.Patch not found");
                    return;
                }
                var args = new object[patch.GetParameters().Length];
                args[0] = restart;
                args[1] = harmonyPrefix;
                patch.Invoke(harmony, args);
                Log("installed");
            }
            catch (Exception e)
            {
                var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
                Log("not installed: " + inner.GetType().Name + ": " + inner.Message);
            }
        }

        // Instead of App.Restart: the note, then the game's own quit. Returns false: the original,
        // which would start the desktop Restarter, does not run.
        static bool Prefix()
        {
            try
            {
                File.WriteAllText(s_Flag, DateTime.UtcNow.ToString("o"));
                Log("the game asked for a restart: the launcher starts it again");
            }
            catch (Exception e)
            {
                Log("cannot write " + s_Flag + ": " + e.Message);
            }
            Application.Quit();
            return false;
        }

        static void Log(string message)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "OXYDROID restart: {0}", message);
        }
    }
}
