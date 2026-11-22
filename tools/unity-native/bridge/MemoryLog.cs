using System;
using System.IO;
using System.Threading;
using UnityEngine;
using UnityEngine.Profiling;

namespace OxyDroid
{
    // One line about the game's memory every minute, always on, so a report after an out-of-memory
    // crash shows what kept growing (a player, 2026-10-14: after 45 minutes the process held 5.4 GB and
    // a 2 KB calloc failed). The numbers:
    //   rss        the process's resident memory (/proc/self/status VmRSS);
    //   maps       its memory mappings (/proc/self/maps lines): Android caps them near 65 000 per
    //              process, and running out of them fails an allocation long before the RAM is gone;
    //   gpu        the mappings of the GPU driver (/dev/kgsl*, /dev/mali*) and their size;
    //   unity      the engine's own allocator, allocated and reserved;
    //   managed    the Mono heap in use (the game's and the mods' C# objects);
    //   fps        frames rendered in that minute per second, and the longest single frame: a report
    //              of "it is slow" needs the number, and the counter on screen is not in any log.
    // Ten seconds in, once, the game's own build string (what its watermark shows), which the report
    // reads back as the game version.
    // The Unity and Mono numbers are read on the main thread (cheap); /proc is read on a pool thread,
    // walking the maps byte by byte so a long list makes no garbage.
    internal static class MemoryLog
    {
        const float PeriodSeconds = 60f;
        static float s_Next = PeriodSeconds;
        static int s_Busy;
        static readonly float s_Start = Time.realtimeSinceStartup;
        static int s_Frames;
        static float s_PeriodStart = Time.realtimeSinceStartup, s_LastFrame = Time.realtimeSinceStartup, s_Slowest;
        static bool s_BuildLogged;

        // Once per rendered frame.
        internal static void Tick()
        {
            float now = Time.realtimeSinceStartup;
            s_Frames++;
            if (now - s_LastFrame > s_Slowest)
                s_Slowest = now - s_LastFrame;
            s_LastFrame = now;
            if (!s_BuildLogged && now - s_Start > 10f)
            {
                s_BuildLogged = true;
                LogGameBuild();
            }
            if (now < s_Next)
                return;
            s_Next = now + PeriodSeconds;
            float seconds = now - s_PeriodStart;
            int fps = seconds > 0f ? (int)(s_Frames / seconds + 0.5f) : 0;
            int slowestMs = (int)(s_Slowest * 1000f + 0.5f);
            s_Frames = 0;
            s_Slowest = 0f;
            s_PeriodStart = now;
            if (Interlocked.CompareExchange(ref s_Busy, 1, 0) != 0)
                return;
            long unityAllocated = 0, unityReserved = 0, managed = 0;
            try
            {
                unityAllocated = Profiler.GetTotalAllocatedMemoryLong();
                unityReserved = Profiler.GetTotalReservedMemoryLong();
                managed = GC.GetTotalMemory(false);
            }
            catch (Exception)
            {
            }
            int minutes = (int)((now - s_Start) / 60f + 0.5f);
            ThreadPool.QueueUserWorkItem(_ =>
            {
                try
                {
                    Write(minutes, unityAllocated, unityReserved, managed, fps, slowestMs);
                }
                catch (Exception e)
                {
                    Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "OXYDROID memory: failed: {0}: {1}",
                                    e.GetType().Name, e.Message);
                }
                finally
                {
                    Interlocked.Exchange(ref s_Busy, 0);
                }
            });
        }

        // BuildWatermark.GetBuildText() of the game, by reflection: this assembly does not reference the
        // game's. "U57-764025-S" and the like; without it the line says why.
        static void LogGameBuild()
        {
            string text;
            try
            {
                var type = Type.GetType("BuildWatermark, Assembly-CSharp");
                var method = type == null ? null : type.GetMethod("GetBuildText",
                    System.Reflection.BindingFlags.Public | System.Reflection.BindingFlags.Static);
                text = method == null ? "unknown (no BuildWatermark.GetBuildText)" : (string)method.Invoke(null, null);
            }
            catch (Exception e)
            {
                text = "unknown (" + e.GetType().Name + ")";
            }
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null, "OXYDROID game: build {0}", text);
        }

        static void Write(int minutes, long unityAllocated, long unityReserved, long managed, int fps, int slowestMs)
        {
            long rss = ReadStatusKb("VmRSS:") * 1024;
            int maps, gpuMaps;
            long gpuBytes;
            CountMaps(out maps, out gpuMaps, out gpuBytes);
            Debug.LogFormat(LogType.Log, LogOption.NoStacktrace, null,
                "OXYDROID memory: {0} min | rss {1} | maps {2} (gpu {3}, {4}) | unity {5} of {6} | managed {7}"
                + " | fps {8}, slowest frame {9} ms",
                minutes, Mb(rss), maps, gpuMaps, Mb(gpuBytes), Mb(unityAllocated), Mb(unityReserved), Mb(managed),
                fps, slowestMs);
        }

        static string Mb(long bytes)
        {
            return (bytes / (1024 * 1024)) + " MB";
        }

        static long ReadStatusKb(string key)
        {
            foreach (var line in File.ReadAllLines("/proc/self/status"))
            {
                if (!line.StartsWith(key, StringComparison.Ordinal))
                    continue;
                long kb = 0;
                foreach (char c in line)
                {
                    if (c >= '0' && c <= '9')
                        kb = kb * 10 + (c - '0');
                }
                return kb;
            }
            return 0;
        }

        // Each line: "start-end perms offset dev inode   path". Counted without building strings.
        static void CountMaps(out int maps, out int gpuMaps, out long gpuBytes)
        {
            maps = 0;
            gpuMaps = 0;
            gpuBytes = 0;
            var buffer = new byte[64 * 1024];
            var line = new byte[512];
            int lineLength = 0;
            using (var stream = new FileStream("/proc/self/maps", FileMode.Open, FileAccess.Read, FileShare.ReadWrite, 1))
            {
                int read;
                while ((read = stream.Read(buffer, 0, buffer.Length)) > 0)
                {
                    for (int i = 0; i < read; i++)
                    {
                        byte b = buffer[i];
                        if (b != (byte)'\n')
                        {
                            if (lineLength < line.Length)
                                line[lineLength++] = b;
                            continue;
                        }
                        maps++;
                        long size;
                        if (IsGpuMapping(line, lineLength, out size))
                        {
                            gpuMaps++;
                            gpuBytes += size;
                        }
                        lineLength = 0;
                    }
                }
            }
        }

        static bool IsGpuMapping(byte[] line, int length, out long size)
        {
            size = 0;
            if (!Contains(line, length, "kgsl") && !Contains(line, length, "/dev/mali"))
                return false;
            long start = 0, end = 0;
            int i = 0;
            for (; i < length && line[i] != (byte)'-'; i++)
                start = start * 16 + Hex(line[i]);
            for (i++; i < length && line[i] != (byte)' '; i++)
                end = end * 16 + Hex(line[i]);
            size = end - start;
            return true;
        }

        static int Hex(byte b)
        {
            if (b >= (byte)'0' && b <= (byte)'9') return b - '0';
            if (b >= (byte)'a' && b <= (byte)'f') return b - 'a' + 10;
            return 0;
        }

        static bool Contains(byte[] line, int length, string word)
        {
            for (int i = 0; i + word.Length <= length; i++)
            {
                int j = 0;
                while (j < word.Length && line[i + j] == (byte)word[j])
                    j++;
                if (j == word.Length)
                    return true;
            }
            return false;
        }
    }
}
