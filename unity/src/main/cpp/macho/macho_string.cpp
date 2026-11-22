#include "macho_string.h"
#include <cstring>
#include <cstdlib>
#include <cstdio>
#include <new>
#include <stdexcept>
#include <system_error>
#include <limits>
#include <locale>
#include <string>
#include <thread>
#include <pthread.h>

static_assert(sizeof(void*) == 8 && sizeof(size_t) == 8, "Only 64-bit ABI supported");
static_assert(sizeof(macho_string) == 24 && alignof(macho_string) == 8, "Darwin string ABI");
#if __BYTE_ORDER__ != __ORDER_LITTLE_ENDIAN__
#error Darwin string shims require little endian
#endif

static constexpr uint64_t long_flag = UINT64_C(1) << 63;
static constexpr size_t short_capacity = 22;
static bool is_long(const macho_string* s) { return (s->words[2] & long_flag) != 0; }
static char* data(macho_string* s) {
    return is_long(s) ? reinterpret_cast<char*>(static_cast<uintptr_t>(s->words[0]))
                      : reinterpret_cast<char*>(s);
}
extern "C" size_t macho_string_size(const macho_string* s) {
    return is_long(s) ? s->words[1] : reinterpret_cast<const unsigned char*>(s)[23];
}
extern "C" const char* macho_string_data(const macho_string* s) {
    return data(const_cast<macho_string*>(s));
}
static size_t checked_add(size_t a, size_t b) {
    constexpr size_t limit = (std::numeric_limits<size_t>::max() >> 1) - 32;
    if (a > limit || b > limit - a) throw std::length_error("Darwin string too large");
    return a + b;
}
static macho_string allocate(size_t size) {
    checked_add(size, 0);
    macho_string result{};
    if (size <= short_capacity) {
        reinterpret_cast<unsigned char*>(&result)[23] = static_cast<unsigned char>(size);
    } else {
        // Count includes the terminator. Match libc++'s 16-byte capacity rounding.
        size_t count = (size + 16) & ~size_t(15);
        result.words[0] = reinterpret_cast<uintptr_t>(::operator new(count));
        result.words[1] = size;
        result.words[2] = long_flag | count;
    }
    data(&result)[size] = '\0';
    return result;
}
extern "C" void macho_string_destroy(macho_string* s) {
    if (is_long(s)) ::operator delete(data(s));
    std::memset(s, 0, sizeof(*s));
}
static void commit(macho_string* target, macho_string value) {
    macho_string_destroy(target);
    *target = value;
}
// Construct before destroying the original: preserves aliases (s += s.data()) and the strong
// exception guarantee. Deliberately prioritize correctness over capacity reuse in this first shim.
extern "C" macho_string* macho_string_append_n(macho_string* s, const char* text, size_t count) {
    const size_t old = macho_string_size(s);
    macho_string result = allocate(checked_add(old, count));
    if (old) std::memcpy(data(&result), data(s), old);
    if (count) std::memcpy(data(&result) + old, text, count);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_append(macho_string* s, const char* text) {
    return macho_string_append_n(s, text, std::strlen(text));
}
static macho_string* assign_n(macho_string* s, const char* text, size_t count) {
    macho_string result = allocate(count);
    if (count) std::memcpy(data(&result), text, count);
    commit(s, result);
    return s;
}
extern "C" macho_string* macho_string_assign(macho_string* s, const char* text) {
    return assign_n(s, text, std::strlen(text));
}
extern "C" macho_string* macho_string_copy(macho_string* s, const macho_string* source) {
    if (s != source) assign_n(s, macho_string_data(source), macho_string_size(source));
    return s;
}
extern "C" macho_string* macho_string_insert(macho_string* s, size_t position, const char* text) {
    const size_t old = macho_string_size(s);
    if (position > old) throw std::out_of_range("Darwin string insert");
    const size_t count = std::strlen(text);
    macho_string result = allocate(checked_add(old, count));
    if (position) std::memcpy(data(&result), data(s), position);
    if (count) std::memcpy(data(&result) + position, text, count);
    if (old != position) std::memcpy(data(&result) + position + count, data(s) + position, old - position);
    commit(s, result);
    return s;
}
extern "C" void macho_string_resize(macho_string* s, size_t count, char fill) {
    const size_t old = macho_string_size(s), copy = old < count ? old : count;
    macho_string result = allocate(count);
    if (copy) std::memcpy(data(&result), data(s), copy);
    if (count > copy) std::memset(data(&result) + copy, static_cast<unsigned char>(fill), count - copy);
    commit(s, result);
}
extern "C" void macho_string_push_back(macho_string* s, char ch) { macho_string_append_n(s, &ch, 1); }
extern "C" macho_string macho_string_concat(const char* prefix, const macho_string* suffix) {
    size_t a = std::strlen(prefix), b = macho_string_size(suffix);
    macho_string result = allocate(checked_add(a, b));
    if (a) std::memcpy(data(&result), prefix, a);
    if (b) std::memcpy(data(&result) + a, macho_string_data(suffix), b);
    return result;
}
extern "C" macho_string macho_string_from_int(int value) {
    char buffer[32];
    std::snprintf(buffer, sizeof(buffer), "%d", value);
    macho_string result{};
    return *macho_string_assign(&result, buffer);
}
// Functions that RETURN a std::string. The return type is not part of a function's mangled name, so
// the loader's layout guard cannot see these: bound to Android's libc++ they would hand an
// Android-layout string to code that reads Apple's. Each builds the Apple object itself.
static macho_string string_from_chars(const char* text, size_t count) {
    macho_string result = allocate(count);
    if (count) std::memcpy(data(&result), text, count);
    return result;
}
static macho_string string_from_ulong(unsigned long value) {
    char buffer[32];
    int n = std::snprintf(buffer, sizeof(buffer), "%lu", value);
    return string_from_chars(buffer, n > 0 ? static_cast<size_t>(n) : 0);
}
static macho_string string_from_ulonglong(unsigned long long value) {
    char buffer[32];
    int n = std::snprintf(buffer, sizeof(buffer), "%llu", value);
    return string_from_chars(buffer, n > 0 ? static_cast<size_t>(n) : 0);
}
// std::locale::name() const. A locale is one pointer in both libraries, so Android's own method
// answers; only its result is rebuilt.
static macho_string locale_name(const std::locale* self) {
    const std::string name = self->name();
    return string_from_chars(name.data(), name.size());
}

static void random_construct(void*, const macho_string* token) {
    // Darwin's arc4random implementation owns no file descriptor. Never write an Android
    // random_device into the (potentially one-byte) Apple object.
    if (macho_string_size(token) != 12 || std::memcmp(macho_string_data(token), "/dev/urandom", 12))
        throw std::system_error(std::make_error_code(std::errc::no_such_file_or_directory), "random_device");
}
static void random_destroy(void*) {}
static unsigned random_next(void*) {
    unsigned result;
    arc4random_buf(&result, sizeof(result));
    return result;
}
static void runtime_error_string(void* self, const macho_string* text) {
    // Use the Android char* constructor, never pass Apple string storage into Android libc++.
    new (self) std::runtime_error(macho_string_data(text));
}

// ---- More of std::string, for Oxygen Not Included's SimDLL ---------------------------------------

static size_t capacity(const macho_string* s) {
    return is_long(s) ? (s->words[2] & ~long_flag) - 1 : short_capacity;
}
// Like allocate(), with room for at least `room` characters.
static macho_string allocate_room(size_t size, size_t room) {
    if (room < size) room = size;
    checked_add(room, 0);
    macho_string result{};
    if (room <= short_capacity) {
        reinterpret_cast<unsigned char*>(&result)[23] = static_cast<unsigned char>(size);
    } else {
        size_t count = (room + 16) & ~size_t(15);
        result.words[0] = reinterpret_cast<uintptr_t>(::operator new(count));
        result.words[1] = size;
        result.words[2] = long_flag | count;
    }
    data(&result)[size] = '\0';
    return result;
}
// size_type copy(char* dest, size_type count, size_type pos) const
static size_t string_copy_out(const macho_string* s, char* dest, size_t count, size_t position) {
    const size_t size = macho_string_size(s);
    if (position > size) throw std::out_of_range("Darwin string copy");
    const size_t n = count < size - position ? count : size - position;
    if (n) std::memcpy(dest, macho_string_data(s) + position, n);
    return n;
}
// int compare(size_type pos, size_type count, const char* text) const
static int string_compare(const macho_string* s, size_t position, size_t count, const char* text) {
    const size_t size = macho_string_size(s);
    if (position > size) throw std::out_of_range("Darwin string compare");
    const size_t mine = count < size - position ? count : size - position;
    const size_t theirs = std::strlen(text);
    const size_t common = mine < theirs ? mine : theirs;
    const int r = common ? std::memcmp(macho_string_data(s) + position, text, common) : 0;
    if (r) return r;
    return mine < theirs ? -1 : (mine > theirs ? 1 : 0);
}
static void string_reserve(macho_string* s, size_t room) {
    if (room <= capacity(s)) return;
    const size_t size = macho_string_size(s);
    macho_string result = allocate_room(size, room);
    if (size) std::memcpy(data(&result), data(s), size);
    commit(s, result);
}
// libc++'s internal reallocation step, called from inline code (append, insert, replace): a new
// buffer of at least old_cap + delta_cap characters holding the first n_copy characters and, after
// a gap of n_add, the tail that followed the n_del deleted ones. The caller writes the gap and sets
// the size afterwards, and relies on the string being in long mode from here on.
static void string_grow_by(macho_string* s, size_t old_cap, size_t delta_cap, size_t old_size,
                           size_t n_copy, size_t n_del, size_t n_add) {
    const bool was_long = is_long(s);
    char* old = data(s);
    size_t room = checked_add(old_cap, delta_cap);
    if (room < 2 * old_cap) room = 2 * old_cap;
    checked_add(room, 0);
    const size_t count = (room + 16) & ~size_t(15);
    char* fresh = static_cast<char*>(::operator new(count));
    if (n_copy) std::memcpy(fresh, old, n_copy);
    const size_t tail = old_size - n_del - n_copy;
    if (tail) std::memcpy(fresh + n_copy + n_add, old + n_copy + n_del, tail);
    if (was_long) ::operator delete(old);
    s->words[0] = reinterpret_cast<uintptr_t>(fresh);
    s->words[2] = long_flag | count;
}
// Constructors: `self` is raw storage, nothing to destroy.
static void string_construct_copy(macho_string* self, const macho_string* source) {
    const size_t size = macho_string_size(source);
    macho_string result = allocate(size);
    if (size) std::memcpy(data(&result), macho_string_data(source), size);
    *self = result;
}
static void string_construct_substr(macho_string* self, const macho_string* source, size_t position,
                                    size_t count, const void* /*allocator*/) {
    const size_t size = macho_string_size(source);
    if (position > size) throw std::out_of_range("Darwin string substring");
    const size_t n = count < size - position ? count : size - position;
    macho_string result = allocate(n);
    if (n) std::memcpy(data(&result), macho_string_data(source) + position, n);
    *self = result;
}
static macho_string* string_assign_char(macho_string* s, char ch) { return assign_n(s, &ch, 1); }
// The conversions go through an Android std::string built from the characters, never the object.
static int string_stoi(const macho_string* s, size_t* index, int base) {
    return std::stoi(std::string(macho_string_data(s), macho_string_size(s)), index, base);
}
static long long string_stoll(const macho_string* s, size_t* index, int base) {
    return std::stoll(std::string(macho_string_data(s), macho_string_size(s)), index, base);
}

// ---- std::mutex and std::condition_variable --------------------------------------------------------
// Apple's objects wrap Darwin's pthread types: a signature word, then opaque bytes (56 for a mutex,
// 40 for a condition variable), all zero after the constexpr constructor. bionic would misread the
// signature as lock state, so the real primitive lives behind it: the mutex inline (bionic's is 40
// bytes and valid when zeroed), the condition variable on the heap (bionic's is 48 bytes, too big),
// created on first use.

struct darwin_mutex { long signature; pthread_mutex_t real; };
static_assert(sizeof(darwin_mutex) <= 64, "does not fit in Darwin's pthread_mutex_t");
struct darwin_cond { long signature; pthread_cond_t* real; };
static_assert(sizeof(darwin_cond) <= 48, "does not fit in Darwin's pthread_cond_t");
struct darwin_unique_lock { darwin_mutex* mutex; bool owns; };

static void mutex_lock(darwin_mutex* m) {
    const int e = pthread_mutex_lock(&m->real);
    if (e) throw std::system_error(e, std::generic_category(), "mutex lock failed");
}
static bool mutex_try_lock(darwin_mutex* m) { return pthread_mutex_trylock(&m->real) == 0; }
static void mutex_unlock(darwin_mutex* m) { pthread_mutex_unlock(&m->real); }
static void mutex_destroy(darwin_mutex* m) { pthread_mutex_destroy(&m->real); }

static pthread_cond_t* cond_real(darwin_cond* c) {
    pthread_cond_t* p = __atomic_load_n(&c->real, __ATOMIC_ACQUIRE);
    if (p) return p;
    pthread_cond_t* fresh = new pthread_cond_t;
    pthread_cond_init(fresh, nullptr);
    pthread_cond_t* expected = nullptr;
    if (__atomic_compare_exchange_n(&c->real, &expected, fresh, false, __ATOMIC_ACQ_REL, __ATOMIC_ACQUIRE))
        return fresh;
    pthread_cond_destroy(fresh);
    delete fresh;
    return expected;
}
static void cond_notify_one(darwin_cond* c) { pthread_cond_signal(cond_real(c)); }
static void cond_notify_all(darwin_cond* c) { pthread_cond_broadcast(cond_real(c)); }
static void cond_wait(darwin_cond* c, darwin_unique_lock* lock) {
    pthread_cond_wait(cond_real(c), &lock->mutex->real);
}
static void cond_destroy(darwin_cond* c) {
    pthread_cond_t* p = __atomic_exchange_n(&c->real, nullptr, __ATOMIC_ACQ_REL);
    if (!p) return;
    pthread_cond_destroy(p);
    delete p;
}

// ---- std::thread's per-thread bookkeeping ----------------------------------------------------------
// The inline thread entry stores its __thread_struct with pthread_setspecific(key, ...), reading the
// key out of the object __thread_local_data() returns. Darwin's pthread_key_t is 8 bytes, bionic's 4,
// so Android's object would hand it half a key and half of whatever follows. This one is ours.

struct darwin_thread_specific { unsigned long key; };
static darwin_thread_specific thread_data_slot;
static pthread_once_t thread_data_once = PTHREAD_ONCE_INIT;
static void thread_data_init() {
    pthread_key_t key;
    if (pthread_key_create(&key, [](void* p) { delete static_cast<std::__thread_struct*>(p); }) != 0)
        std::abort();
    thread_data_slot.key = static_cast<unsigned int>(key);
}
static darwin_thread_specific* thread_local_data() {
    pthread_once(&thread_data_once, thread_data_init);
    return &thread_data_slot;
}

extern "C" void* macho_string_import(const char* name) {
    struct binding { const char* name; void* address; };
#define B(s, f) {s, reinterpret_cast<void*>(f)}
#define S "_ZNSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
#define K "_ZNKSt3__112basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEE"
    static const binding imports[] = {
        B(S "6appendEPKc", macho_string_append), B(S "6appendEPKcm", macho_string_append_n),
        B(S "6assignEPKc", macho_string_assign), B(S "6insertEmPKc", macho_string_insert),
        B(S "6resizeEmc", macho_string_resize), B(S "9push_backEc", macho_string_push_back),
        B(S "aSERKS5_", macho_string_copy), B(S "D1Ev", macho_string_destroy), B(S "D2Ev", macho_string_destroy),
        B(S "6assignEPKcm", assign_n), B(S "7reserveEm", string_reserve),
        B(S "9__grow_byEmmmmmm", string_grow_by), B(S "aSEc", string_assign_char),
        B(S "C1ERKS5_", string_construct_copy), B(S "C2ERKS5_", string_construct_copy),
        B(S "C1ERKS5_mmRKS4_", string_construct_substr), B(S "C2ERKS5_mmRKS4_", string_construct_substr),
        B(K "4copyEPcmm", string_copy_out), B(K "7compareEmmPKc", string_compare),
        B("_ZNSt3__14stoiERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEEPmi", string_stoi),
        B("_ZNSt3__15stollERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEEPmi", string_stoll),
        B("_ZNSt3__15mutex4lockEv", mutex_lock), B("_ZNSt3__15mutex8try_lockEv", mutex_try_lock),
        B("_ZNSt3__15mutex6unlockEv", mutex_unlock),
        B("_ZNSt3__15mutexD1Ev", mutex_destroy), B("_ZNSt3__15mutexD2Ev", mutex_destroy),
        B("_ZNSt3__118condition_variable10notify_oneEv", cond_notify_one),
        B("_ZNSt3__118condition_variable10notify_allEv", cond_notify_all),
        B("_ZNSt3__118condition_variable4waitERNS_11unique_lockINS_5mutexEEE", cond_wait),
        B("_ZNSt3__118condition_variableD1Ev", cond_destroy), B("_ZNSt3__118condition_variableD2Ev", cond_destroy),
        B("_ZNSt3__119__thread_local_dataEv", thread_local_data),
        B("_ZNSt3__19to_stringEi", macho_string_from_int),
        B("_ZNSt3__19to_stringEm", string_from_ulong), B("_ZNSt3__19to_stringEy", string_from_ulonglong),
        B("_ZNKSt3__16locale4nameEv", locale_name),
        B("_ZNSt3__1plIcNS_11char_traitsIcEENS_9allocatorIcEEEENS_12basic_stringIT_T0_T1_EEPKS6_RKS9_", macho_string_concat),
        B("_ZNSt3__113random_deviceC1ERKNS_12basic_stringIcNS_11char_traitsIcEENS_9allocatorIcEEEE", random_construct),
        B("_ZNSt3__113random_deviceD1Ev", random_destroy), B("_ZNSt3__113random_deviceclEv", random_next),
        B("_ZNSt13runtime_errorC2ERKNSt3__112basic_stringIcNS0_11char_traitsIcEENS0_9allocatorIcEEEE", runtime_error_string),
    };
#undef S
#undef K
#undef B
    for (const auto& entry : imports) if (std::strcmp(name, entry.name) == 0) return entry.address;
    return nullptr;
}
