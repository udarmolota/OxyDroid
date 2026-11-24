using System;
using System.Collections;
using System.Collections.Generic;
using System.IO;
using System.Reflection;
using UnityEngine;

namespace OxyDroid
{
    // Mods the launcher has just installed (Mods screen, Workshop download) are switched on by
    // themselves at the next start. The game lists a new mod folder only when it starts, and lists it
    // switched off; the player then had to switch it on in the game's Mods menu as well, after doing so
    // in the launcher. The launcher writes the new folders ("Local/<folder>", one per line) to the file
    // OXYDROID_MODS_ENABLE names; right after the game has read its mod list and found the new folders
    // (KMod's LoadModDBAndInitialize) and before it loads mod content, the listed mods are switched on
    // and the list saved, so they load in this very start. The file is then removed.
    //
    // Reached by reflection, like RestartPatch: if a step fails, the game is left as it is and the log
    // says why.
    internal static class ModsEnable
    {
        static string s_File;
        static HashSet<string> s_Wanted;

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
                    return;

                MethodInfo target = null;
                var asm = Type.GetType("KMod.Manager, Assembly-CSharp")?.Assembly;
                if (asm != null)
                    foreach (var t in asm.GetTypes())
                    {
                        target = t.GetMethod("LoadModDBAndInitialize", BindingFlags.Public | BindingFlags.NonPublic
                                                                       | BindingFlags.Instance | BindingFlags.Static);
                        if (target != null) break;
                    }
                var harmonyType = Type.GetType("HarmonyLib.Harmony, 0Harmony");
                var harmonyMethodType = Type.GetType("HarmonyLib.HarmonyMethod, 0Harmony");
                if (target == null || harmonyType == null || harmonyMethodType == null)
                {
                    Log("not installed: " + (target == null ? "LoadModDBAndInitialize" : "Harmony") + " not found");
                    return;
                }
                var postfix = typeof(ModsEnable).GetMethod(nameof(Postfix), BindingFlags.NonPublic | BindingFlags.Static);
                object harmony = Activator.CreateInstance(harmonyType, "com.oxydroid.modsenable");
                object harmonyPostfix = Activator.CreateInstance(harmonyMethodType, postfix);
                MethodInfo patch = null;
                foreach (var m in harmonyType.GetMethods(BindingFlags.Public | BindingFlags.Instance))
                {
                    var ps = m.GetParameters();
                    if (m.Name == "Patch" && ps.Length >= 3 && ps[0].ParameterType == typeof(MethodBase)
                        && ps[1].ParameterType == harmonyMethodType && ps[2].ParameterType == harmonyMethodType)
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
                args[0] = target;
                args[2] = harmonyPostfix;   // (original, prefix, postfix, ...)
                patch.Invoke(harmony, args);
                Log("installed on " + target.DeclaringType.FullName + "." + target.Name + " for " + s_Wanted.Count + " mod(s)");
            }
            catch (Exception e)
            {
                var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
                Log("not installed: " + inner.GetType().Name + ": " + inner.Message);
            }
        }

        static void Postfix(object __instance)
        {
            try
            {
                object manager = FindManager(__instance);
                if (manager == null)
                {
                    Log("the mod manager was not found: nothing switched on");
                    return;
                }
                var mods = Member(manager, "mods") as IEnumerable;
                if (mods == null)
                {
                    Log("the mod list was not found: nothing switched on");
                    return;
                }
                int on = 0;
                foreach (var mod in mods)
                {
                    object label = Member(mod, "label");
                    string id = Member(label, "id") as string;
                    string platform = Member(label, "distribution_platform")?.ToString();
                    if (id == null || platform == null || !s_Wanted.Contains(platform + "/" + id))
                        continue;
                    var isOn = mod.GetType().GetMethod("IsEnabledForActiveDlc", BindingFlags.Public | BindingFlags.Instance);
                    if (isOn != null && isOn.GetParameters().Length == 0 && (bool)isOn.Invoke(mod, null))
                        continue;
                    var set = mod.GetType().GetMethod("SetEnabledForActiveDlc", BindingFlags.Public | BindingFlags.Instance);
                    if (set == null)
                    {
                        Log("SetEnabledForActiveDlc not found: nothing switched on");
                        return;
                    }
                    set.Invoke(mod, new object[] { true });
                    on++;
                    Log("switched on " + platform + "/" + id);
                }
                if (on > 0)
                {
                    var save = manager.GetType().GetMethod("Save", BindingFlags.Public | BindingFlags.NonPublic | BindingFlags.Instance,
                                                           null, Type.EmptyTypes, null);
                    save?.Invoke(manager, null);
                }
                File.Delete(s_File);
                Log(on + " new mod(s) switched on");
            }
            catch (Exception e)
            {
                var inner = e is TargetInvocationException && e.InnerException != null ? e.InnerException : e;
                Log("failed: " + inner.GetType().Name + ": " + inner.Message);
            }
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
