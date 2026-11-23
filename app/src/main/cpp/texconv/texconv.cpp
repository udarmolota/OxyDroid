// liboxydroidtex.so — the game's textures for GPUs without BC formats (Mali, PowerVR).
//
// Oxygen Not Included keeps its art in Unity SerializedFiles (sharedassets0.assets, resources.assets,
// ...), compressed for PC GPUs: DXT5 (about 1.4 GB), DXT1 (about 170 MB) and one BC7 texture. A GPU
// without textureCompressionBC makes Unity unpack each of them to RGBA32 on load: four to eight times
// the memory. Here they are converted once to ASTC, which every such GPU samples, IN PLACE:
//
//   DXT5, BC7 -> ASTC 4x4: 16 bytes per 4x4 block, exactly as the source, for every mip level. The
//                data is overwritten where it lies and only the format field changes.
//   DXT1      -> ASTC 6x6: 3.56 bits per pixel against DXT1's 4, so the converted mip chain fits in
//                the space of the old one (checked per texture; a tiny texture where it does not fit
//                is left alone). The format and the data size fields change.
//
// Two kinds of object are converted:
//
//   Texture2D (class 28), its pixels streamed from the matching .resS. Nearly all of the game's art.
//   Texture2DArray (class 187), its pixels inside the .assets itself: the three background arrays
//                (bgarray, bgarray_liquids, bgarray_liquids_specular in sharedassets2.assets), which
//                unpacked would be about 450 MB more. Every layer's mip chain is converted. For the
//                DXT1 array the data gets shorter inside the record: the fields that follow it move up
//                behind it and the object's size in the object table is reduced; the bytes left over
//                at the record's end are a gap between two objects, which the format allows.
//
// Neither file changes its size, and no other object moves. Each mip level is decoded (bcdec) and
// re-encoded (astcenc) as authored; mip chains are not regenerated.
//
// Being killed half-way must not leave a texture whose data and fields disagree. Before a texture is
// overwritten, the fields about to change and the original bytes go to a journal file; the journal is
// emptied once the texture is complete. A journal found at the start is played back (the texture
// returns to BC) and that texture is simply converted again. Textures already converted are ASTC in
// the file and are not touched, so the pass can be run at every launch: when nothing is left it
// writes nothing.
//
// Layout facts (Unity 6000.3.17f1, SerializedFile version 22, no type trees; checked against the
// game's files with research/tools): header ints are big-endian, the rest little-endian. Strings and
// byte arrays are length-prefixed and 4-aligned.
//   Texture2D: m_Name, 2 bools (m_DownscaleFallback, m_IsAlphaChannelOptional; no
//     m_ForcedFallbackFormat in this version), m_Width, m_Height, m_CompleteImageSize, m_MipsStripped,
//     m_TextureFormat (TextureFormat), m_MipCount, 3 bools, m_MipmapLimitGroupName,
//     m_StreamingMipmaps, m_StreamingMipmapsPriority, m_ImageCount, m_TextureDimension,
//     GLTextureSettings (6 ints), m_LightmapFormat, m_ColorSpace, m_PlatformBlob, image data,
//     m_StreamData {offset u64, size u32, path}.
//   Texture2DArray: m_Name, 2 bools, m_ColorSpace, m_Format (GraphicsFormat, not TextureFormat),
//     m_Width, m_Height, m_Depth, m_MipCount, one int (0), m_DataSize, GLTextureSettings (6 ints),
//     m_UsageMode, m_IsReadable, one int (0), image data (layer after layer, each a whole mip chain),
//     m_StreamData.
// A record whose parsed length is not the length the object table gives is a layout this code does not
// know: the file is left alone.
#include <jni.h>
#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <functional>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

// Vulkan is linked, not dlopen()ed: in the launcher's process liboxydroidlinker (the box64 engine's)
// exports its own dlopen and dlsym, which crash until a box64 game has started (pc 0, S25 2026-11-23,
// the first launch of a new instance). Calls through the import table never meet them.
#include <vulkan/vulkan.h>

#include "astcenc/astcenc.h"

#define BCDEC_IMPLEMENTATION
#define BCDEC_STATIC
#include "bcdec.h"

#define TAG "OxyDroid/texconv"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

// TextureFormat (Texture2D.m_TextureFormat)
enum { FMT_DXT1 = 10, FMT_DXT5 = 12, FMT_BC7 = 25, FMT_ASTC_4x4 = 48, FMT_ASTC_6x6 = 50 };
// GraphicsFormat (Texture2DArray.m_Format), from UnityEngine.CoreModule of this Unity version
enum { GFX_DXT1_SRGB = 96, GFX_DXT1 = 97, GFX_DXT5_SRGB = 100, GFX_DXT5 = 101, GFX_BC7_SRGB = 108, GFX_BC7 = 109,
       GFX_ASTC_4x4_SRGB = 129, GFX_ASTC_4x4 = 130, GFX_ASTC_6x6_SRGB = 133, GFX_ASTC_6x6 = 134 };

// The encoder's effort (astcenc's 0..100 scale; 10 is its "fast" preset). The pass runs once per
// install while the player waits, on about 1.5 gigapixels.
const float QUALITY = 10.0f;
const int MAX_THREADS = 6;   // each worker holds one texture unpacked: up to ~90 MB for a 4096x4096

// ------------------------------------------------------------------ files

struct Mapping {
    uint8_t* p = nullptr;
    size_t size = 0;
    bool open(const char* path) {
        int fd = ::open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) return false;
        struct stat st;
        if (fstat(fd, &st) != 0 || st.st_size <= 0) { ::close(fd); return false; }
        size = (size_t)st.st_size;
        void* m = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
        ::close(fd);
        if (m == MAP_FAILED) { size = 0; return false; }
        p = (uint8_t*)m;
        return true;
    }
    void close() {
        if (p) { msync(p, size, MS_SYNC); munmap(p, size); }
        p = nullptr; size = 0;
    }
};

struct Reader {
    const uint8_t* b;
    size_t size, p;
    bool ok = true;
    Reader(const uint8_t* buf, size_t n, size_t at) : b(buf), size(n), p(at) {}
    bool have(size_t n) { if (n > size || p > size - n) ok = false; return ok; }
    uint32_t u32be() { if (!have(4)) return 0; uint32_t v = (uint32_t)b[p] << 24 | (uint32_t)b[p+1] << 16 | (uint32_t)b[p+2] << 8 | b[p+3]; p += 4; return v; }
    uint64_t u64be() { uint64_t hi = u32be(), lo = u32be(); return hi << 32 | lo; }
    uint32_t u32() { if (!have(4)) return 0; uint32_t v; memcpy(&v, b + p, 4); p += 4; return v; }
    uint64_t u64() { if (!have(8)) return 0; uint64_t v; memcpy(&v, b + p, 8); p += 8; return v; }
    uint8_t u8() { if (!have(1)) return 0; return b[p++]; }
    void skip(size_t n) { if (have(n)) p += n; }
    void align() { p = (p + 3) & ~(size_t)3; }
    std::string str() { uint32_t n = u32(); if (!have(n)) return ""; std::string s((const char*)b + p, n); p += n; align(); return s; }
    void cstr() { while (p < size && b[p]) p++; p++; }
    void skipArray() { uint32_t n = u32(); skip(n); align(); }
};

// One convertible texture: where its BC data is, what it becomes and which fields of the .assets
// change with it.
struct Texture {
    int width, height, mips, layers;
    int bc;               // the source: 1 (DXT1), 3 (DXT5) or 7
    int block;            // the ASTC block edge: 4 or 6
    bool inAssets;        // the data lies in the .assets (a Texture2DArray), not in the .resS
    uint64_t offset;      // of the data in that file
    uint32_t size;        // of the BC data, every layer and mip level
    uint32_t newSize;     // of the ASTC data
    // Texture2DArray whose data gets shorter: the bytes after the (4-aligned) data up to the record's
    // end, which move up behind the new data. 0 otherwise.
    uint32_t tail;
    int patches;
    struct { uint64_t pos; uint32_t value; } patch[4];   // u32 fields of the .assets and their new values
    void set(uint64_t pos, uint32_t value) { patch[patches].pos = pos; patch[patches].value = value; patches++; }
    // What an interrupted rewrite may have touched in the data's file, from `offset`.
    uint32_t restoreSize() const { return tail ? ((size + 3) & ~3u) + tail : size; }
};

uint32_t chainSize(int w, int h, int mips, int blockEdge, int bytesPerBlock) {
    uint64_t total = 0;
    for (int i = 0; i < mips; i++) {
        total += (uint64_t)((w + blockEdge - 1) / blockEdge) * ((h + blockEdge - 1) / blockEdge) * bytesPerBlock;
        w = w > 1 ? w / 2 : 1;
        h = h > 1 ? h / 2 : 1;
    }
    return total > 0xffffffffu ? 0 : (uint32_t)total;
}

// Sizes for a texture of t.width x t.height x t.mips x t.layers in BC `t.bc`. False when `dataSize`
// is not a plain BC chain of that shape, or when the ASTC chain would not fit in it.
bool plan(Texture& t, uint32_t dataSize) {
    if (t.width <= 0 || t.height <= 0 || t.width > 16384 || t.height > 16384) return false;
    if (t.mips <= 0 || t.mips > 16 || t.layers <= 0 || t.layers > 4096) return false;
    uint64_t bcChain = chainSize(t.width, t.height, t.mips, 4, t.bc == 1 ? 8 : 16);
    if (bcChain == 0 || bcChain * t.layers != dataSize) return false;
    t.block = t.bc == 1 ? 6 : 4;
    uint64_t astc = (uint64_t)chainSize(t.width, t.height, t.mips, t.block, 16) * t.layers;
    if (astc == 0 || astc > dataSize) return false;   // a small DXT1: ASTC 6x6 would not fit
    t.size = dataSize;
    t.newSize = (uint32_t)astc;
    return true;
}

// One Texture2D at `at`. False: the record's length does not come out as recorded.
bool parseTexture2D(const Mapping& a, size_t at, size_t size, const std::string& resSName, Texture& t, bool& convertible) {
    Reader rd(a.p, a.size, at);
    rd.str();                                   // m_Name
    rd.u8(); rd.u8(); rd.align();               // m_DownscaleFallback, m_IsAlphaChannelOptional
    t.width = (int)rd.u32(); t.height = (int)rd.u32();
    size_t posCompleteSize = rd.p; uint32_t completeSize = rd.u32();
    rd.u32();                                   // m_MipsStripped
    size_t posFormat = rd.p; int format = (int)rd.u32();
    t.mips = (int)rd.u32();
    uint8_t readable = rd.u8(); rd.u8(); rd.u8(); rd.align();   // m_IsReadable, m_IsPreProcessed, m_IgnoreMipmapLimit
    rd.str();                                   // m_MipmapLimitGroupName
    rd.u8(); rd.align();                        // m_StreamingMipmaps
    rd.u32();                                   // m_StreamingMipmapsPriority
    uint32_t imageCount = rd.u32();
    uint32_t dimension = rd.u32();
    rd.skip(24);                                // GLTextureSettings
    rd.u32(); rd.u32();                         // m_LightmapFormat, m_ColorSpace
    rd.skipArray();                             // m_PlatformBlob
    uint32_t inlineLen = rd.u32(); rd.skip(inlineLen); rd.align();   // image data
    t.offset = rd.u64();
    size_t posStreamSize = rd.p; uint32_t streamSize = rd.u32();
    std::string path = rd.str();
    if (!rd.ok || rd.p - at != size) return false;

    convertible = false;
    t.bc = format == FMT_DXT1 ? 1 : format == FMT_DXT5 ? 3 : format == FMT_BC7 ? 7 : 0;
    if (!t.bc) return true;
    // A texture the game reads back on the CPU stays as it is: its pixels must decode as written.
    if (readable || imageCount != 1 || dimension != 2 || inlineLen != 0 || path != resSName) return true;
    if (streamSize == 0 || streamSize != completeSize) return true;
    t.layers = 1;
    t.inAssets = false;
    t.tail = 0;
    if (!plan(t, streamSize)) return true;
    t.patches = 0;
    t.set(posFormat, t.block == 4 ? FMT_ASTC_4x4 : FMT_ASTC_6x6);
    t.set(posCompleteSize, t.newSize);
    t.set(posStreamSize, t.newSize);
    convertible = true;
    return true;
}

// One Texture2DArray at `at`; `posObjectSize` is where the object table keeps this record's length.
bool parseTexture2DArray(const Mapping& a, size_t at, size_t size, size_t posObjectSize, Texture& t, bool& convertible) {
    Reader rd(a.p, a.size, at);
    rd.str();                                   // m_Name
    rd.u8(); rd.u8(); rd.align();               // m_DownscaleFallback, m_IsAlphaChannelOptional
    rd.u32();                                   // m_ColorSpace
    size_t posFormat = rd.p; int format = (int)rd.u32();
    t.width = (int)rd.u32(); t.height = (int)rd.u32();
    t.layers = (int)rd.u32();
    t.mips = (int)rd.u32();
    rd.u32();
    size_t posDataSize = rd.p; uint32_t dataSize = rd.u32();
    rd.skip(24);                                // GLTextureSettings
    rd.u32();                                   // m_UsageMode
    rd.u8(); rd.align();                        // m_IsReadable: the game only binds these arrays to a material
    rd.u32();
    size_t posArrayLen = rd.p; uint32_t arrayLen = rd.u32();
    size_t dataAt = rd.p;
    rd.skip(arrayLen); rd.align();
    size_t tailAt = rd.p;
    rd.u64();                                   // m_StreamData.offset
    uint32_t streamSize = rd.u32();
    rd.str();                                   // m_StreamData.path
    if (!rd.ok || rd.p - at != size) return false;

    convertible = false;
    bool srgb = format == GFX_DXT1_SRGB || format == GFX_DXT5_SRGB || format == GFX_BC7_SRGB;
    t.bc = (format == GFX_DXT1 || format == GFX_DXT1_SRGB) ? 1 : (format == GFX_DXT5 || format == GFX_DXT5_SRGB) ? 3
         : (format == GFX_BC7 || format == GFX_BC7_SRGB) ? 7 : 0;
    if (!t.bc) return true;
    if (arrayLen == 0 || arrayLen != dataSize || streamSize != 0) return true;   // streamed: not seen in this game
    if (!plan(t, dataSize)) return true;
    t.inAssets = true;
    t.offset = dataAt;
    t.patches = 0;
    t.set(posFormat, t.block == 4 ? (srgb ? GFX_ASTC_4x4_SRGB : GFX_ASTC_4x4) : (srgb ? GFX_ASTC_6x6_SRGB : GFX_ASTC_6x6));
    if (t.newSize == t.size) {
        t.tail = 0;
    } else {
        // Shorter data: the stream fields follow it at once, and the record ends after them.
        t.tail = (uint32_t)(at + size - tailAt);
        t.set(posDataSize, t.newSize);
        t.set(posArrayLen, t.newSize);
        t.set(posObjectSize, (uint32_t)(dataAt - at) + ((t.newSize + 3) & ~3u) + t.tail);
    }
    convertible = true;
    return true;
}

// ------------------------------------------------------------------ journal

// u32 magic, u32 patch count, 4 x {u64 position in the .assets, u32 old value}, u32 data file (0 the
// .resS, 1 the .assets), u64 data offset, u32 data size, then that many bytes of the original data,
// then u32 magic again (the journal is whole only with it: a kill while writing it leaves it short,
// and then nothing was overwritten yet).
const uint32_t JOURNAL_MAGIC = 0x324a544f;   // "OTJ2"
const size_t JOURNAL_HEAD = 4 + 4 + 4 * 12 + 4 + 8 + 4;

struct Journal {
    int fd = -1;
    bool open(const char* path) { fd = ::open(path, O_RDWR | O_CREAT | O_CLOEXEC, 0600); return fd >= 0; }
    void close() { if (fd >= 0) ::close(fd); fd = -1; }
    static bool writeAll(int fd, const void* data, size_t n) {
        const uint8_t* p = (const uint8_t*)data;
        while (n > 0) {
            ssize_t w = ::write(fd, p, n);
            if (w <= 0) return false;
            p += w; n -= (size_t)w;
        }
        return true;
    }
    bool begin(const Texture& t, const uint8_t* assets, const uint8_t* original) {
        if (ftruncate(fd, 0) != 0 || lseek(fd, 0, SEEK_SET) != 0) return false;
        uint8_t head[JOURNAL_HEAD];
        memset(head, 0, sizeof head);
        uint32_t count = (uint32_t)t.patches, where = t.inAssets ? 1 : 0, size = t.restoreSize();
        memcpy(head, &JOURNAL_MAGIC, 4);
        memcpy(head + 4, &count, 4);
        for (int i = 0; i < t.patches; i++) {
            memcpy(head + 8 + i * 12, &t.patch[i].pos, 8);
            memcpy(head + 8 + i * 12 + 8, assets + t.patch[i].pos, 4);   // the value there now
        }
        memcpy(head + 56, &where, 4);
        memcpy(head + 60, &t.offset, 8);
        memcpy(head + 68, &size, 4);
        return writeAll(fd, head, sizeof head) && writeAll(fd, original, size) && writeAll(fd, &JOURNAL_MAGIC, 4);
    }
    void end() { if (ftruncate(fd, 0) != 0) LOGW("journal: cannot empty it"); }

    // Puts back the texture an interrupted run was in the middle of. False only when a whole journal
    // does not fit these files (they are then not the files it was written for: do not convert).
    bool replay(Mapping& assets, Mapping& resS) {
        struct stat st;
        if (fstat(fd, &st) != 0 || (size_t)st.st_size < JOURNAL_HEAD + 4) return true;   // empty or cut short
        std::vector<uint8_t> j((size_t)st.st_size);
        if (pread(fd, j.data(), j.size(), 0) != (ssize_t)j.size()) return true;
        uint32_t magic, count, where, size, tail;
        uint64_t offset;
        memcpy(&magic, &j[0], 4); memcpy(&count, &j[4], 4);
        memcpy(&where, &j[56], 4); memcpy(&offset, &j[60], 8); memcpy(&size, &j[68], 4);
        if (magic != JOURNAL_MAGIC || count > 4 || j.size() != JOURNAL_HEAD + (size_t)size + 4) return true;
        memcpy(&tail, &j[JOURNAL_HEAD + size], 4);
        if (tail != JOURNAL_MAGIC) return true;
        Mapping& data = where ? assets : resS;
        bool fits = offset <= data.size && size <= data.size - offset;
        for (uint32_t i = 0; i < count && fits; i++) {
            uint64_t pos;
            memcpy(&pos, &j[8 + i * 12], 8);
            fits = pos <= assets.size && assets.size - pos >= 4;
        }
        if (!fits) { LOGW("journal does not match these files"); return false; }
        memcpy(data.p + offset, &j[JOURNAL_HEAD], size);
        for (uint32_t i = 0; i < count; i++) {
            uint64_t pos;
            memcpy(&pos, &j[8 + i * 12], 8);
            memcpy(assets.p + pos, &j[8 + i * 12 + 8], 4);
        }
        msync(resS.p, resS.size, MS_SYNC);
        msync(assets.p, assets.size, MS_SYNC);
        end();
        LOGI("journal: one interrupted texture put back (%u bytes at %llu of the %s)", size,
             (unsigned long long)offset, where ? ".assets" : ".resS");
        return true;
    }
};

// ------------------------------------------------------------------ conversion

struct Encoder {
    astcenc_context* ctx4 = nullptr;
    astcenc_context* ctx6 = nullptr;
    bool init() {
        astcenc_config c;
        if (astcenc_config_init(ASTCENC_PRF_LDR, 4, 4, 1, QUALITY, ASTCENC_FLG_USE_ALPHA_WEIGHT, &c) != ASTCENC_SUCCESS) return false;
        if (astcenc_context_alloc(&c, 1, &ctx4) != ASTCENC_SUCCESS) return false;
        if (astcenc_config_init(ASTCENC_PRF_LDR, 6, 6, 1, QUALITY, ASTCENC_FLG_USE_ALPHA_WEIGHT, &c) != ASTCENC_SUCCESS) return false;
        return astcenc_context_alloc(&c, 1, &ctx6) == ASTCENC_SUCCESS;
    }
    ~Encoder() {
        if (ctx4) astcenc_context_free(ctx4);
        if (ctx6) astcenc_context_free(ctx6);
    }
};

// One level of BC blocks at `src` unpacked to RGBA8 rows of ((w+3)/4)*4 pixels in `padded`.
void decodeLevel(int bc, const uint8_t* src, int w, int h, std::vector<uint8_t>& padded) {
    int bw = (w + 3) / 4, bh = (h + 3) / 4, pw = bw * 4;
    int step = bc == 1 ? 8 : 16;
    padded.resize((size_t)pw * bh * 4 * 4);
    for (int by = 0; by < bh; by++) {
        for (int bx = 0; bx < bw; bx++, src += step) {
            uint8_t* dst = &padded[((size_t)by * 4 * pw + (size_t)bx * 4) * 4];
            if (bc == 1) bcdec_bc1(src, dst, pw * 4);
            else if (bc == 3) bcdec_bc3(src, dst, pw * 4);
            else bcdec_bc7(src, dst, pw * 4);
        }
    }
}

// Every layer's mip chain, BC at `src` -> ASTC in `out` (t.newSize bytes).
bool convertData(Encoder& enc, const Texture& t, const uint8_t* src, std::vector<uint8_t>& rgba,
                 std::vector<uint8_t>& padded, std::vector<uint8_t>& out) {
    static const astcenc_swizzle swizzle = { ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A };
    out.resize(t.newSize);
    astcenc_context* ctx = t.block == 4 ? enc.ctx4 : enc.ctx6;
    int bcBytes = t.bc == 1 ? 8 : 16;
    size_t in = 0, at = 0;
    for (int layer = 0; layer < t.layers; layer++) {
        int w = t.width, h = t.height;
        for (int level = 0; level < t.mips; level++) {
            int pw = ((w + 3) / 4) * 4, ph = ((h + 3) / 4) * 4;
            decodeLevel(t.bc, src + in, w, h, padded);
            in += (size_t)(pw / 4) * (ph / 4) * bcBytes;
            const uint8_t* pixels = padded.data();
            if (pw != w || ph != h) {   // a size that is not a multiple of 4: the encoder takes tight rows
                rgba.resize((size_t)w * h * 4);
                for (int y = 0; y < h; y++) memcpy(&rgba[(size_t)y * w * 4], &padded[(size_t)y * pw * 4], (size_t)w * 4);
                pixels = rgba.data();
            }
            size_t levelBytes = (size_t)((w + t.block - 1) / t.block) * ((h + t.block - 1) / t.block) * 16;
            if (at + levelBytes > out.size()) return false;
            void* slice = (void*)pixels;
            astcenc_image image;
            image.dim_x = (unsigned)w; image.dim_y = (unsigned)h; image.dim_z = 1;
            image.data_type = ASTCENC_TYPE_U8;
            image.data = &slice;
            astcenc_error e = astcenc_compress_image(ctx, &image, &swizzle, &out[at], levelBytes, 0);
            astcenc_compress_reset(ctx);
            if (e != ASTCENC_SUCCESS) return false;
            at += levelBytes;
            w = w > 1 ? w / 2 : 1;
            h = h > 1 ? h / 2 : 1;
        }
    }
    return at == out.size();
}

struct Job {
    Mapping assets, resS;
    Journal journal;
    std::vector<Texture> textures;
    std::atomic<size_t> next{0};
    std::atomic<uint64_t> doneBytes{0};
    std::atomic<int> converted{0}, failed{0};
    std::atomic<bool> stop{false};
    std::mutex commit;
};

void worker(Job* job) {
    Encoder enc;
    if (!enc.init()) { job->failed++; job->stop = true; return; }
    std::vector<uint8_t> rgba, padded, out, tail;
    for (;;) {
        if (job->stop) return;
        size_t i = job->next.fetch_add(1);
        if (i >= job->textures.size()) return;
        const Texture& t = job->textures[i];
        uint8_t* data = (t.inAssets ? job->assets.p : job->resS.p) + t.offset;
        if (!convertData(enc, t, data, rgba, padded, out)) {
            job->failed++;
            job->doneBytes += t.size;
            continue;   // nothing was written: the texture stays BC
        }
        {
            std::lock_guard<std::mutex> lock(job->commit);
            if (!job->journal.begin(t, job->assets.p, data)) {   // no journal, no overwrite (the disk is full?)
                job->failed++;
                job->stop = true;
                return;
            }
            if (t.tail) tail.assign(data + ((t.size + 3) & ~3u), data + ((t.size + 3) & ~3u) + t.tail);
            memcpy(data, out.data(), out.size());
#ifdef TEXCONV_TEST_DIE_MID_COMMIT   // the test program only: die with the data written and the fields not
            TEXCONV_TEST_DIE_MID_COMMIT
#endif
            if (t.tail) {   // the fields that followed the old data now follow the new, shorter data
                size_t padded4 = (t.newSize + 3) & ~3u;
                memset(data + t.newSize, 0, padded4 - t.newSize);
                memcpy(data + padded4, tail.data(), t.tail);
            }
            for (int k = 0; k < t.patches; k++) memcpy(job->assets.p + t.patch[k].pos, &t.patch[k].value, 4);
            job->journal.end();
        }
        job->converted++;
        job->doneBytes += t.size;
    }
}

// Lists the file's convertible textures. False: a file this code does not understand.
bool listTextures(const Mapping& a, const std::string& resSName, size_t resSSize, std::vector<Texture>& out, const char** why) {
    if (a.size < 48) { *why = "too short"; return false; }
    Reader hd(a.p, a.size, 8);
    uint32_t version = hd.u32be();
    hd.p = 16;
    uint8_t endian = hd.u8();
    if (version != 22 || endian != 0) { *why = "not a version 22 little-endian file"; return false; }
    hd.p = 20;
    hd.u32be();                    // metadata size
    hd.u64be();                    // file size
    uint64_t dataOffset = hd.u64be();
    Reader md(a.p, a.size, 48);
    md.cstr();                     // Unity version
    md.u32();                      // target platform
    if (md.u8() != 0) { *why = "embedded type trees"; return false; }
    uint32_t typeCount = md.u32();
    if (!md.ok || typeCount > 100000) { *why = "bad type count"; return false; }
    std::vector<int32_t> classIds(typeCount);
    for (uint32_t i = 0; i < typeCount; i++) {
        classIds[i] = (int32_t)md.u32();
        md.skip(3);                // is stripped, script type index
        if (classIds[i] == 114) md.skip(16);   // script id
        md.skip(16);               // old type hash
    }
    uint32_t objectCount = md.u32();
    if (!md.ok || objectCount > 10000000) { *why = "bad object table"; return false; }
    for (uint32_t i = 0; i < objectCount; i++) {
        md.align();
        md.u64();                  // path id
        uint64_t start = md.u64();
        size_t posObjectSize = md.p;
        uint32_t size = md.u32();
        uint32_t typeId = md.u32();
        if (!md.ok) { *why = "truncated object table"; return false; }
        if (typeId >= typeCount) continue;
        int classId = classIds[typeId];
        if (classId != 28 && classId != 187) continue;   // Texture2D, Texture2DArray
        uint64_t at = dataOffset + start;
        if (at > a.size || size > a.size - at) { *why = "object outside the file"; return false; }
        Texture t;
        bool convertible;
        if (classId == 28) {
            if (!parseTexture2D(a, (size_t)at, size, resSName, t, convertible)) { *why = "unknown Texture2D layout"; return false; }
            if (convertible && (t.offset > resSSize || t.size > resSSize - t.offset)) { *why = "texture data outside the resS"; return false; }
        } else {
            if (!parseTexture2DArray(a, (size_t)at, size, posObjectSize, t, convertible)) { *why = "unknown Texture2DArray layout"; return false; }
        }
        if (convertible) out.push_back(t);
    }
    // Two records sharing the same bytes cannot both be rewritten: leave any such pair alone.
    std::vector<Texture> unique;
    for (size_t i = 0; i < out.size(); i++) {
        bool shared = false;
        for (size_t k = 0; k < out.size() && !shared; k++)
            shared = k != i && out[k].inAssets == out[i].inAssets
                && out[k].offset < out[i].offset + out[i].size && out[i].offset < out[k].offset + out[k].size;
        if (!shared) unique.push_back(out[i]);
    }
    out.swap(unique);
    return true;
}

// Converts one pair in place; `progress(doneBytes, totalBytes)` is called a few times a second from
// the calling thread. False after logging why the pair was left alone.
bool convertPair(const char* assetsPath, const char* resSPath, const char* journalPath,
                 const std::function<void(uint64_t, uint64_t)>& progress, std::string& summary) {
    auto started = std::chrono::steady_clock::now();
    Job job;
    bool ok = [&]() -> bool {
        if (!job.assets.open(assetsPath)) { LOGW("%s: cannot open", assetsPath); return false; }
        if (!job.resS.open(resSPath)) { LOGW("%s: cannot open", resSPath); return false; }
        if (!job.journal.open(journalPath)) { LOGW("%s: cannot open the journal", journalPath); return false; }
        if (!job.journal.replay(job.assets, job.resS)) return false;
        std::string name = resSPath;
        size_t slash = name.find_last_of('/');
        if (slash != std::string::npos) name = name.substr(slash + 1);
        const char* why = "";
        if (!listTextures(job.assets, name, job.resS.size, job.textures, &why)) { LOGW("%s: %s", assetsPath, why); return false; }
        uint64_t total = 0;
        for (const Texture& t : job.textures) total += t.size;
        if (job.textures.empty()) { summary = "nothing to convert"; return true; }

        int threads = (int)sysconf(_SC_NPROCESSORS_ONLN);
        if (threads < 1) threads = 1;
        if (threads > MAX_THREADS) threads = MAX_THREADS;
        std::vector<std::thread> pool;
        std::atomic<int> running{threads};
        for (int i = 0; i < threads; i++) pool.emplace_back([&] { worker(&job); running--; });
        while (running > 0) {
            usleep(250000);
            if (progress) progress(job.doneBytes.load(), total);
        }
        for (std::thread& t : pool) t.join();

        long ms = (long)std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count();
        char line[256];
        snprintf(line, sizeof line, "%d of %zu textures converted to ASTC (%d failed) in %ld ms on %d threads, %llu MB of BC data",
                 job.converted.load(), job.textures.size(), job.failed.load(), ms, threads, (unsigned long long)(total >> 20));
        summary = line;
        return job.failed.load() == 0 || job.converted.load() > 0;
    }();
    job.journal.close();
    job.resS.close();
    job.assets.close();
    if (ok) LOGI("%s: %s", assetsPath, summary.c_str());
    return ok;
}

}  // namespace

// What the phone's GPU samples: bit 0 BC, bit 1 ASTC LDR. -1 when Vulkan could not be asked.
extern "C" JNIEXPORT jint JNICALL
Java_com_oxydroid_game_TextureConverter_nativeGpuFormats(JNIEnv*, jclass) {
    PFN_vkCreateInstance createInstance = vkCreateInstance;
    PFN_vkDestroyInstance destroyInstance = vkDestroyInstance;
    PFN_vkEnumeratePhysicalDevices enumerate = vkEnumeratePhysicalDevices;
    PFN_vkGetPhysicalDeviceFeatures features = vkGetPhysicalDeviceFeatures;
    int result = -1;
    {
        VkApplicationInfo app = {};
        app.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        app.apiVersion = VK_API_VERSION_1_0;
        VkInstanceCreateInfo info = {};
        info.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        info.pApplicationInfo = &app;
        VkInstance instance = VK_NULL_HANDLE;
        if (createInstance(&info, nullptr, &instance) == VK_SUCCESS && instance) {
            uint32_t count = 1;
            VkPhysicalDevice gpu = VK_NULL_HANDLE;
            VkResult r = enumerate(instance, &count, &gpu);
            if ((r == VK_SUCCESS || r == VK_INCOMPLETE) && count >= 1 && gpu) {
                VkPhysicalDeviceFeatures f = {};
                features(gpu, &f);
                result = (f.textureCompressionBC ? 1 : 0) | (f.textureCompressionASTC_LDR ? 2 : 0);
            }
            destroyInstance(instance, nullptr);
        }
    }
    return result;
}

// How many bytes of BC texture data the pair still holds (0: nothing to convert), -1: not readable.
// Reads only; a journal left by an interrupted run is not played back here.
extern "C" JNIEXPORT jlong JNICALL
Java_com_oxydroid_game_TextureConverter_nativePending(JNIEnv* env, jclass, jstring jAssets, jstring jResS) {
    const char* assetsPath = env->GetStringUTFChars(jAssets, nullptr);
    const char* resSPath = env->GetStringUTFChars(jResS, nullptr);
    jlong result = -1;
    Mapping a;
    struct stat st;
    if (a.open(assetsPath) && stat(resSPath, &st) == 0) {
        std::string name = resSPath;
        size_t slash = name.find_last_of('/');
        if (slash != std::string::npos) name = name.substr(slash + 1);
        std::vector<Texture> textures;
        const char* why = "";
        if (listTextures(a, name, (size_t)st.st_size, textures, &why)) {
            result = 0;
            for (const Texture& t : textures) result += t.size;
        } else {
            LOGW("%s: %s", assetsPath, why);
        }
    }
    if (a.p) munmap(a.p, a.size);   // nothing was written: no msync
    env->ReleaseStringUTFChars(jAssets, assetsPath);
    env->ReleaseStringUTFChars(jResS, resSPath);
    return result;
}

// Converts one .assets/.resS pair in place. Returns a one-line summary, or null after logging why the
// pair was left alone. `progress` (may be null) has onProgress(long doneBytes, long totalBytes),
// called a few times a second from this thread.
extern "C" JNIEXPORT jstring JNICALL
Java_com_oxydroid_game_TextureConverter_nativeConvert(JNIEnv* env, jclass, jstring jAssets, jstring jResS,
                                                      jstring jJournal, jobject progress) {
    const char* assetsPath = env->GetStringUTFChars(jAssets, nullptr);
    const char* resSPath = env->GetStringUTFChars(jResS, nullptr);
    const char* journalPath = env->GetStringUTFChars(jJournal, nullptr);
    jmethodID onProgress = nullptr;
    if (progress) onProgress = env->GetMethodID(env->GetObjectClass(progress), "onProgress", "(JJ)V");
    if (env->ExceptionCheck()) { env->ExceptionClear(); onProgress = nullptr; }
    std::string summary;
    bool ok = convertPair(assetsPath, resSPath, journalPath, [&](uint64_t done, uint64_t total) {
        if (!onProgress) return;
        env->CallVoidMethod(progress, onProgress, (jlong)done, (jlong)total);
        if (env->ExceptionCheck()) env->ExceptionClear();
    }, summary);
    env->ReleaseStringUTFChars(jAssets, assetsPath);
    env->ReleaseStringUTFChars(jResS, resSPath);
    env->ReleaseStringUTFChars(jJournal, journalPath);
    return ok ? env->NewStringUTF(summary.c_str()) : nullptr;
}
