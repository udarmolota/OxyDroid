/*
 * macho_darwin.c - Darwin libc symbols a macOS arm64 dylib imports that bionic either lacks or
 * lays out differently. The loader asks macho_darwin_import() before it looks in bionic.
 *
 * Three kinds of entries:
 *  - data objects with a Darwin layout (the ctype table, stderr's variable);
 *  - functions bionic has under another name or with another struct (errno, assert, stat, time);
 *  - variadic functions. Apple's arm64 ABI passes every variadic argument on the stack in 8-byte
 *    slots and its va_list is a plain pointer; AAPCS64 uses the registers first and a five-field
 *    va_list. A call to printf from the dylib would therefore make bionic read its arguments from
 *    registers that hold nothing. Each such import goes through a two-instruction entry that hands
 *    the caller's stack pointer to a C function, which rebuilds an AAPCS64 va_list over it.
 *
 * Deliberately inert: the socket calls and signal(). SimDLL's only network code is the HTTP
 * listener of Klei's profiler, which the game does not start; Darwin's sockaddr and option
 * constants differ from Linux's, so socket() and getaddrinfo() fail cleanly instead of running
 * with mistranslated arguments. signal() installs nothing: the process's handlers belong to Mono
 * and the Unity player.
 */
#include <assert.h>
#include <ctype.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdarg.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <time.h>

#include "logger.h"
#include "macho_darwin.h"

#define LOG_TAG "oxydroid-macho"

/* ------------------------------------------------------------------------------------------ */
/* ctype. Darwin's isalpha() and friends are inline: for c < 128 they test bits in
 * _DefaultRuneLocale.__runetype[c]. The struct below reproduces Darwin's _RuneLocale up to the
 * three 256-entry tables (the fields after them are only reached for non-ASCII characters through
 * functions the dylib does not import). */

#define DARWIN_CTYPE_A 0x00000100u  /* alpha */
#define DARWIN_CTYPE_C 0x00000200u  /* control */
#define DARWIN_CTYPE_D 0x00000400u  /* digit */
#define DARWIN_CTYPE_G 0x00000800u  /* graph */
#define DARWIN_CTYPE_L 0x00001000u  /* lower */
#define DARWIN_CTYPE_P 0x00002000u  /* punct */
#define DARWIN_CTYPE_S 0x00004000u  /* space */
#define DARWIN_CTYPE_U 0x00008000u  /* upper */
#define DARWIN_CTYPE_X 0x00010000u  /* hex digit */
#define DARWIN_CTYPE_B 0x00020000u  /* blank */
#define DARWIN_CTYPE_R 0x00040000u  /* print */

typedef struct {
    char magic[8];
    char encoding[32];
    void* sgetrune;
    void* sputrune;
    int32_t invalid_rune;
    uint32_t runetype[256];
    int32_t maplower[256];
    int32_t mapupper[256];
    uint8_t rest[256];      /* range tables, variable data, character classes: all empty */
} darwin_rune_locale_t;

_Static_assert(__builtin_offsetof(darwin_rune_locale_t, runetype) == 60, "Darwin _RuneLocale layout");
_Static_assert(__builtin_offsetof(darwin_rune_locale_t, maplower) == 1084, "Darwin _RuneLocale layout");

static darwin_rune_locale_t macho_rune_locale;
static FILE* macho_stderrp;
static FILE* macho_stdoutp;
static FILE* macho_stdinp;
static pthread_once_t macho_darwin_once = PTHREAD_ONCE_INIT;

static void macho_darwin_init(void) {
    darwin_rune_locale_t* r = &macho_rune_locale;
    memcpy(r->magic, "RuneMagA", 8);
    memcpy(r->encoding, "NONE", 5);
    r->invalid_rune = 0xFFFD;
    for (int c = 0; c < 256; c++) {
        uint32_t t = 0;
        if (c < 128) {
            if (isalpha(c))  t |= DARWIN_CTYPE_A;
            if (iscntrl(c))  t |= DARWIN_CTYPE_C;
            if (isdigit(c))  t |= DARWIN_CTYPE_D | (uint32_t)(c - '0');   /* low byte: digit value */
            if (isgraph(c))  t |= DARWIN_CTYPE_G;
            if (islower(c))  t |= DARWIN_CTYPE_L;
            if (ispunct(c))  t |= DARWIN_CTYPE_P;
            if (isspace(c))  t |= DARWIN_CTYPE_S;
            if (isupper(c))  t |= DARWIN_CTYPE_U;
            if (isxdigit(c)) t |= DARWIN_CTYPE_X;
            if (c == ' ' || c == '\t') t |= DARWIN_CTYPE_B;
            if (isprint(c))  t |= DARWIN_CTYPE_R;
        }
        r->runetype[c] = t;
        r->maplower[c] = c < 128 ? tolower(c) : c;
        r->mapupper[c] = c < 128 ? toupper(c) : c;
    }
    macho_stderrp = stderr;
    macho_stdoutp = stdout;
    macho_stdinp = stdin;
}

static int macho_tolower(int c) { return (c >= 0 && c < 128) ? tolower(c) : c; }
static int macho_toupper(int c) { return (c >= 0 && c < 128) ? toupper(c) : c; }

/* ------------------------------------------------------------------------------------------ */
/* Same function, different name or shape. */

static int* macho_error(void) { return &errno; }

static void macho_assert_rtn(const char* func, const char* file, int line, const char* expr) {
    __assert2(file ? file : "?", line, func ? func : "?", expr ? expr : "?");
}

/* Mach's monotonic clock with a 1:1 timebase: nanoseconds. */
static uint64_t macho_mach_absolute_time(void) {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (uint64_t)ts.tv_sec * 1000000000ull + (uint64_t)ts.tv_nsec;
}
static int macho_mach_timebase_info(uint32_t* info) {
    if (info) { info[0] = 1; info[1] = 1; }   /* numer, denom */
    return 0;                                  /* KERN_SUCCESS */
}

/* The fortified FD_SET check of the macOS 11 SDK: nonzero means "fd fits". */
static int macho_check_fd_set_overflow(int fd, const void* set, int unlimited) {
    (void)set; (void)unlimited;
    return fd >= 0 && fd < 1024;
}

/* Darwin's struct stat (arm64, 64-bit inodes): 144 bytes, fields in another order than bionic's. */
typedef struct {
    int32_t  st_dev;
    uint16_t st_mode;
    uint16_t st_nlink;
    uint64_t st_ino;
    uint32_t st_uid;
    uint32_t st_gid;
    int32_t  st_rdev;
    int32_t  pad0;
    int64_t  atime_sec, atime_nsec;
    int64_t  mtime_sec, mtime_nsec;
    int64_t  ctime_sec, ctime_nsec;
    int64_t  birth_sec, birth_nsec;
    int64_t  st_size;
    int64_t  st_blocks;
    int32_t  st_blksize;
    uint32_t st_flags;
    uint32_t st_gen;
    int32_t  st_lspare;
    int64_t  st_qspare[2];
} darwin_stat_t;
_Static_assert(sizeof(darwin_stat_t) == 144, "Darwin struct stat layout");

static void macho_fill_stat(darwin_stat_t* out, const struct stat* st) {
    memset(out, 0, sizeof(*out));
    out->st_dev = (int32_t)st->st_dev;
    out->st_mode = (uint16_t)st->st_mode;      /* the S_IF* and permission bits have equal values */
    out->st_nlink = (uint16_t)st->st_nlink;
    out->st_ino = st->st_ino;
    out->st_uid = st->st_uid;
    out->st_gid = st->st_gid;
    out->st_rdev = (int32_t)st->st_rdev;
    out->atime_sec = st->st_atim.tv_sec;  out->atime_nsec = st->st_atim.tv_nsec;
    out->mtime_sec = st->st_mtim.tv_sec;  out->mtime_nsec = st->st_mtim.tv_nsec;
    out->ctime_sec = st->st_ctim.tv_sec;  out->ctime_nsec = st->st_ctim.tv_nsec;
    out->birth_sec = st->st_ctim.tv_sec;  out->birth_nsec = st->st_ctim.tv_nsec;
    out->st_size = st->st_size;
    out->st_blocks = st->st_blocks;
    out->st_blksize = (int32_t)st->st_blksize;
}
static int macho_stat(const char* path, darwin_stat_t* out) {
    struct stat st;
    int r = stat(path, &st);
    if (r == 0 && out) macho_fill_stat(out, &st);
    return r;
}
static int macho_fstat(int fd, darwin_stat_t* out) {
    struct stat st;
    int r = fstat(fd, &st);
    if (r == 0 && out) macho_fill_stat(out, &st);
    return r;
}
static int macho_lstat(const char* path, darwin_stat_t* out) {
    struct stat st;
    int r = lstat(path, &st);
    if (r == 0 && out) macho_fill_stat(out, &st);
    return r;
}

/* Thread-local destructors registered by the dylib's own code (thread_local objects). */
extern int __cxa_thread_atexit_impl(void (*fn)(void*), void* arg, void* dso_handle);
static void macho_tlv_atexit(void (*fn)(void*), void* arg) {
    static char dso;   /* any address inside this library identifies it to bionic */
    __cxa_thread_atexit_impl(fn, arg, &dso);
}

/* ------------------------------------------------------------------------------------------ */
/* Inert on purpose (see the header comment). */

static int macho_socket_refused(int domain, int type, int protocol) {
    (void)domain; (void)type; (void)protocol;
    errno = EAFNOSUPPORT;
    return -1;
}
static int macho_getaddrinfo_refused(const char* node, const char* service, const void* hints, void** res) {
    (void)node; (void)service; (void)hints;
    if (res) *res = NULL;
    return 4;   /* EAI_FAIL on Darwin */
}
static void* macho_signal_ignored(int sig, void* handler) {
    (void)sig; (void)handler;
    return NULL;   /* SIG_DFL: "the previous handler was the default" */
}

/* ------------------------------------------------------------------------------------------ */
/* Variadic functions: Apple callers put the variable arguments on the stack. */

typedef struct {
    void* stack;
    void* gr_top;
    void* vr_top;
    int gr_offs;
    int vr_offs;
} aapcs64_va_list_t;
_Static_assert(sizeof(aapcs64_va_list_t) == sizeof(va_list), "AAPCS64 va_list layout");

/* An AAPCS64 va_list whose register save areas are exhausted (offsets >= 0), so that every
 * va_arg, integer or floating-point, is taken from the stack area: Apple's layout exactly. */
static void macho_make_va(va_list* out, void* stack_args) {
    aapcs64_va_list_t raw = { stack_args, NULL, NULL, 0, 0 };
    memcpy(out, &raw, sizeof(raw));
}

__attribute__((visibility("hidden"))) int macho_printf_va(const char* fmt, void* args) {
    va_list ap;
    macho_make_va(&ap, args);
    return vprintf(fmt, ap);
}
__attribute__((visibility("hidden"))) int macho_fprintf_va(FILE* f, const char* fmt, void* args) {
    va_list ap;
    macho_make_va(&ap, args);
    return vfprintf(f, fmt, ap);
}
__attribute__((visibility("hidden"))) int macho_sprintf_va(char* buf, const char* fmt, void* args) {
    va_list ap;
    macho_make_va(&ap, args);
    return vsprintf(buf, fmt, ap);
}
__attribute__((visibility("hidden"))) int macho_snprintf_va(char* buf, size_t n, const char* fmt, void* args) {
    va_list ap;
    macho_make_va(&ap, args);
    return vsnprintf(buf, n, fmt, ap);
}
__attribute__((visibility("hidden"))) int macho_sscanf_va(const char* s, const char* fmt, void* args) {
    va_list ap;
    macho_make_va(&ap, args);
    return vsscanf(s, fmt, ap);
}
/* fcntl's third argument is an int or a pointer: one 8-byte slot either way. */
__attribute__((visibility("hidden"))) int macho_fcntl_va(int fd, int cmd, void* args) {
    return fcntl(fd, cmd, *(uintptr_t*)args);
}

/* The v* forms take Apple's va_list, the pointer itself. */
static int macho_vprintf(const char* fmt, void* apple_ap) { return macho_printf_va(fmt, apple_ap); }
static int macho_vfprintf(FILE* f, const char* fmt, void* apple_ap) { return macho_fprintf_va(f, fmt, apple_ap); }
static int macho_vsprintf(char* buf, const char* fmt, void* apple_ap) { return macho_sprintf_va(buf, fmt, apple_ap); }
static int macho_vsnprintf(char* buf, size_t n, const char* fmt, void* apple_ap) {
    return macho_snprintf_va(buf, n, fmt, apple_ap);
}

/* Entry points: sp at entry is the address of the first variable argument. */
#define MACHO_VARIADIC_ENTRY(name, reg, target)   \
    void name(void);                              \
    __asm__(".text\n"                             \
            ".balign 4\n"                         \
            ".hidden " #name "\n"                 \
            ".global " #name "\n"                 \
            ".type " #name ", %function\n"        \
            #name ":\n"                           \
            "    mov " #reg ", sp\n"              \
            "    b " #target "\n"                 \
            ".size " #name ", .-" #name "\n")

MACHO_VARIADIC_ENTRY(macho_apple_printf,   x1, macho_printf_va);
MACHO_VARIADIC_ENTRY(macho_apple_fprintf,  x2, macho_fprintf_va);
MACHO_VARIADIC_ENTRY(macho_apple_sprintf,  x2, macho_sprintf_va);
MACHO_VARIADIC_ENTRY(macho_apple_snprintf, x3, macho_snprintf_va);
MACHO_VARIADIC_ENTRY(macho_apple_sscanf,   x2, macho_sscanf_va);
MACHO_VARIADIC_ENTRY(macho_apple_fcntl,    x2, macho_fcntl_va);

/* ------------------------------------------------------------------------------------------ */

typedef struct { const char* name; void* addr; } macho_darwin_entry_t;

void* macho_darwin_import(const char* name) {
    static const macho_darwin_entry_t table[] = {
        /* data */
        { "_DefaultRuneLocale", &macho_rune_locale },
        { "__stderrp", &macho_stderrp },
        { "__stdoutp", &macho_stdoutp },
        { "__stdinp",  &macho_stdinp },
        /* thread-local variables */
        { "_tlv_bootstrap", (void*)macho_tlv_thunk },
        { "_tlv_atexit",    (void*)macho_tlv_atexit },
        /* renamed or reshaped */
        { "__error",        (void*)macho_error },
        { "__tolower",      (void*)macho_tolower },
        { "__toupper",      (void*)macho_toupper },
        { "__assert_rtn",   (void*)macho_assert_rtn },
        { "mach_absolute_time", (void*)macho_mach_absolute_time },
        { "mach_timebase_info", (void*)macho_mach_timebase_info },
        { "__darwin_check_fd_set_overflow", (void*)macho_check_fd_set_overflow },
        { "stat",  (void*)macho_stat },
        { "fstat", (void*)macho_fstat },
        { "lstat", (void*)macho_lstat },
        /* inert */
        { "socket",      (void*)macho_socket_refused },
        { "getaddrinfo", (void*)macho_getaddrinfo_refused },
        { "signal",      (void*)macho_signal_ignored },
        /* variadic */
        { "printf",    (void*)macho_apple_printf },
        { "fprintf",   (void*)macho_apple_fprintf },
        { "sprintf",   (void*)macho_apple_sprintf },
        { "snprintf",  (void*)macho_apple_snprintf },
        { "sscanf",    (void*)macho_apple_sscanf },
        { "fcntl",     (void*)macho_apple_fcntl },
        { "vprintf",   (void*)macho_vprintf },
        { "vfprintf",  (void*)macho_vfprintf },
        { "vsprintf",  (void*)macho_vsprintf },
        { "vsnprintf", (void*)macho_vsnprintf },
    };
    pthread_once(&macho_darwin_once, macho_darwin_init);
    for (size_t i = 0; i < sizeof(table) / sizeof(table[0]); i++)
        if (strcmp(name, table[i].name) == 0) return table[i].addr;
    return NULL;
}
