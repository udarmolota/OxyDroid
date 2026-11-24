using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using UnityEngine;

namespace OxyDroid
{
    // Mods the launcher has just installed (Mods screen, Workshop download) are switched on by
    // themselves. The game lists a new mod folder only when it starts, and lists it switched off; the
    // player then had to switch it on in the game's Mods menu as well. The launcher writes the new
    // folders ("Local/<folder>", one per line) to the file OXYDROID_MODS_ENABLE names, and here:
    //   1. whenever the game adds a mod to its list (KMod.Manager.Subscribe) and right after it has read
    //      its list (LoadModDBAndInitialize), the listed mods found there are switched on — early enough,
    //      they load in this very start;
    //   2. at the main menu, a listed mod that is still off (the game added it after loading mods) is
    //      switched on and the game restarts once, the way the launcher restarts it after a change of
    //      mods in the game (RestartPatch's flag), so it loads.
    // The file keeps what is not found yet and is removed when every listed mod is on.
    //
    // Reached by reflection, like RestartPatch: if a step fails, the game is left as it is and the log
    // says why. The first attempt (2026-11-24) only looked after LoadModDBAndInitialize, where the new
    // folders were not listed yet: "0 new mod(s) switched on".
    internal static class ModsEnable
    {
        static string s_File;
        static HashSet<string> s_Wanted;
        static bool s_Restarted;

        internal static void Install()
        {
            s_File = Environment.GetEnvironmentVariable("OXYDROID_MODS_ENABLE");
            if (string.IsNullOrEmpty(s_File) || !File.Exists(s_File))
                return;
            try
            {
                s_Wanted = new HashSet<string>(StringComparer.OrdinalIgnoreCase);
                foreach (var line in File.ReadAllLines(s_File))
                    if (line.Trim().Length > 0) s_Wanted.Add(line.Trim());
                if (s_Wanted.Count == 0)
                {
                    File.Delete(s_File);
                    return;
                }
                var managerType = Type.GetType("KMod.Manager, Assembly-CSharp");
                var modType = Type.GetType("KMod.Mod, Assembly-CSharp");
                if (managerType == null)
                {
                    Log("not installed: KMod.Manager not found");
                    return;
                }
                const BindingFlags all = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
                int patched = 0;
                var load = managerType.GetMethod("LoadModDBAndInitialize", all);
                if (load != null && Patch(load, nameof(AfterManagerStep))) patched++;
                foreach (var m in managerType.GetMethods(all))
                {
                    var ps = m.GetParameters();
                    if (m.Name == "Subscribe" && ps.Length >= 1 && modType != null && ps[0].ParameterType == modType
                        && Patch(m, nameof(AfterManagerStep))) patched++;
                }
                var mainMenu = Type.GetType("MainMenu, Assembly-CSharp");
                var onMenu = mainMenu?.GetMethod("OnPrefabInit", all);
                if (onMenu != null && Patch(onMenu, nameof(AtMainMenu))) patched++;
                Log("installed on " + patched + " method(s) for " + s_Wanted.Count + " mod(s)");
            }
            catch (Exception e)
            {
                var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
                Log("not installed: " + inner.GetType().Name + ": " + inner.Message);
            }
        }

        // ---- Harmony, by reflection ----

        static object s_Harmony;
        static Type s_HarmonyMethodType;
        static MethodInfo s_Patch;

        static bool Patch(MethodBase target, string postfixName)
        {
            try
            {
                if (s_Patch == null)
                {
                    var harmonyType = Type.GetType("HarmonyLib.Harmony, 0Harmony");
                    s_HarmonyMethodType = Type.GetType("HarmonyLib.HarmonyMethod, 0Harmony");
                    if (harmonyType == null || s_HarmonyMethodType == null) { Log("Harmony not found"); return false; }
                    s_Harmony = Activator.CreateInstance(harmonyType, "com.oxydroid.modsenable");
                    foreach (var m in harmonyType.GetMethods(BindingFlags.Public | BindingFlags.Instance))
                    {
                        var ps = m.GetParameters();
                        if (m.Name == "Patch" && ps.Length >= 3 && ps[0].ParameterType == typeof(MethodBase)
                            && ps[1].ParameterType == s_HarmonyMethodType && ps[2].ParameterType == s_HarmonyMethodType)
                        {
                            s_Patch = m;
                            break;
                        }
                    }
                    if (s_Patch == null) { Log("Harmony.Patch not found"); return false; }
                }
                var postfix = typeof(ModsEnable).GetMethod(postfixName, BindingFlags.NonPublic | BindingFlags.Static);
                var args = new object[s_Patch.GetParameters().Length];
                args[0] = target;
                args[2] = Activator.CreateInstance(s_HarmonyMethodType, postfix);   // (original, prefix, postfix, ...)
                s_Patch.Invoke(s_Harmony, args);
                return true;
            }
            catch (Exception e)
            {
                var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
                Log("cannot patch " + target.DeclaringType?.Name + "." + target.Name + ": " + inner.Message);
                return false;
            }
        }

        // ---- the postfixes ----

        static void AfterManagerStep(object __instance)
        {
            TryEnable(FindManager(__instance), "early");
        }

        static void AtMainMenu()
        {
            if (s_Wanted == null || s_Wanted.Count == 0)
                return;
            int on = TryEnable(FindManager(null), "main menu");
            if (on <= 0 || s_Restarted)
                return;
            // Switched on after the game had loaded its mods: they load at the next start.
            s_Restarted = true;
            string flag = Environment.GetEnvironmentVariable("OXYDROID_RESTART_FLAG");
            if (string.IsNullOrEmpty(flag))
            {
                Log("switched on at the main menu: they load at the next start");
                return;
            }
            try
            {
                File.WriteAllText(flag, DateTime.UtcNow.ToString("o"));
                Log("switched on at the main menu: restarting once so they load");
                Application.Quit();
            }
            catch (Exception e)
            {
                Log("cannot restart: " + e.Message);
            }
        }

        /** Switches on the wanted mods the manager lists; returns how many it switched on now. */
        static int TryEnable(object manager, string when)
        {
            if (s_Wanted == null || s_Wanted.Count == 0)
                return 0;
            int on = 0, listed = 0;
            try
            {
                if (manager == null)
                    return 0;
                var mods = Member(manager, "mods") as IEnumerable;
                if (mods == null)
                    return 0;
                var found = new List<string>();
                foreach (var mod in mods)
                {
                    listed++;
                    object label = Member(mod, "label");
                    string id = Member(label, "id") as string;
                    string platform = Member(label, "distribution_platform")?.ToString();
                    string key = platform + "/" + id;
                    if (id == null || platform == null || !s_Wanted.Contains(key))
                        continue;
                    found.Add(key);
                    var isOn = mod.GetType().GetMethod("IsEnabledForActiveDlc", BindingFlags.Public | BindingFlags.Instance, null, Type.EmptyTypes, null);
                    if (isOn != null && (bool)isOn.Invoke(mod, null))
                        continue;
                    var set = mod.GetType().GetMethod("SetEnabledForActiveDlc", BindingFlags.Public | BindingFlags.Instance);
                    if (set == null)
                    {
                        Log("SetEnabledForActiveDlc not found");
                        return on;
                    }
                    set.Invoke(mod, new object[] { true });
                    on++;
                    Log("switched on " + key + " (" + when + ")");
                }
                if (on > 0)
                {
                    var save = manager.GetType().GetMethod("Save", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance,
                                                           null, Type.EmptyTypes, null);
                    save?.Invoke(manager, null);
                }
                foreach (var key in found) s_Wanted.Remove(key);
                if (s_Wanted.Count == 0) File.Delete(s_File);
                else File.WriteAllLines(s_File, new List<string>(s_Wanted).ToArray());
                if (found.Count > 0 || when == "main menu")
                    Log(when + ": " + listed + " mod(s) listed, " + on + " switched on, " + s_Wanted.Count + " still to find");
            }
            catch (Exception e)
            {
                var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
                Log("failed (" + when + "): " + inner.GetType().Name + ": " + inner.Message);
            }
            return on;
        }

        /** KMod.Manager: the patched method's own object, or Global.Instance.modManager. */
        static object FindManager(object instance)
        {
            var managerType = Type.GetType("KMod.Manager, Assembly-CSharp");
            if (instance != null && managerType != null && managerType.IsInstanceOfType(instance))
                return instance;
            var global = Type.GetType("Global, Assembly-CSharp");
            object g = global == null ? null : Member(null, "Instance", global);
            return g == null ? null : Member(g, "modManager");
        }

        static object Member(object obj, string name, Type type = null)
        {
            if (obj == null && type == null) return null;
            type = type ?? obj.GetType();
            const BindingFlags flags = BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance | BindingFlags.Static;
            for (var t = type; t != null; t = t.BaseType)
            {
                var f = t.GetField(name, flags);
                if (f != null) return f.GetValue(obj);
                var p = t.GetProperty(name, flags);
                if (p != null && p.GetIndexParameters().Length == 0) return p.GetValue(obj, null);
            }
            return null;
        }

        static void Log(string message)
        {
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "OXYDROID mods: {0}", message);
        }
    }
}
