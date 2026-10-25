/*
 * winloader - userspace ELF loader for aarch64 Android.
 *
 * SELinux denies execve() on files in the app data directory for targetSdk >= 29 but
 * still allows nativeLibraryDir. This binary ships as libwinloader.so and does what
 * binfmt_elf would do for the real target (box64, wine, pulseaudio ...): maps the
 * executable and its PT_INTERP, builds the initial stack/auxv and jumps to the entry.
 * For bionic targets it also LD_PRELOADs libwinloader_shim.so (see winloader_shim.c).
 *
 * Usage: libwinloader.so [-a argv0] <program> [args...]
 * Env:   WINLOADER_FALLBACK  program that receives targets which are not aarch64 ELFs
 *        WINLOADER_NOSECCOMP when set, the shim is not injected
 *
 * Freestanding: no libc, no relocations, no TLS. Keep it that way.
 */


typedef unsigned char u8;
typedef unsigned short u16;
typedef unsigned int u32;
typedef unsigned long u64;
typedef long s64;
typedef unsigned long size_t;

#ifndef NULL
#define NULL ((void *)0)
#endif

#define NR_openat 56
#define NR_getcwd 17
#define NR_close 57
#define NR_read 63
#define NR_write 64
#define NR_pread64 67
#define NR_readlinkat 78
#define NR_exit_group 94
#define NR_rt_sigaction 134
#define NR_rt_sigprocmask 135
#define NR_prctl 167
#define NR_munmap 215
#define NR_mprotect 226
#define NR_mmap 222
#define NR_execve 221
#define NR_execveat 281

#define AT_FDCWD (-100)
#define AT_EMPTY_PATH 0x1000
#define O_CLOEXEC 02000000

#define PROT_READ 1
#define PROT_WRITE 2
#define PROT_EXEC 4
#define PROT_BTI 0x10 /* arm64: set by the bionic linker on exec segments, never by guests */
#define MAP_PRIVATE 0x02
#define MAP_FIXED 0x10
#define MAP_ANONYMOUS 0x20
#define MAP_FIXED_NOREPLACE 0x100000

#define ENOENT 2
#define ENOEXEC 8
#define ENOMEM 12
#define EACCES 13
#define EINVAL 22
#define ENAMETOOLONG 36
#define ENOSYS 38

#define SIGSYS 31
#define SA_SIGINFO 4
#define SA_NODEFER 0x40000000
#define SIG_UNBLOCK 1

#define AUXV_NULL 0
#define AUXV_PHDR 3
#define AUXV_PHENT 4
#define AUXV_PHNUM 5
#define AUXV_PAGESZ 6
#define AUXV_BASE 7
#define AUXV_FLAGS 8
#define AUXV_ENTRY 9
#define AUXV_EXECFN 31


#define VIS __attribute__((visibility("hidden")))


static inline long sc(long n, long a, long b, long c, long d, long e, long f)
{
    register long x8 __asm__("x8") = n;
    register long x0 __asm__("x0") = a;
    register long x1 __asm__("x1") = b;
    register long x2 __asm__("x2") = c;
    register long x3 __asm__("x3") = d;
    register long x4 __asm__("x4") = e;
    register long x5 __asm__("x5") = f;
    __asm__ volatile("svc #0" : "+r"(x0) : "r"(x8), "r"(x1), "r"(x2), "r"(x3), "r"(x4), "r"(x5) : "memory", "cc");
    return x0;
}

static inline int is_err(long r) { return (unsigned long)r > (unsigned long)-4096L; }


VIS void *memcpy(void *d, const void *s, size_t n)
{
    volatile u8 *dd = d;
    const volatile u8 *ss = s;
    while (n--) *dd++ = *ss++;
    return d;
}

VIS void *memmove(void *d, const void *s, size_t n)
{
    volatile u8 *dd = d;
    const volatile u8 *ss = s;
    if (dd < ss) { while (n--) *dd++ = *ss++; }
    else { dd += n; ss += n; while (n--) *--dd = *--ss; }
    return d;
}

VIS void *memset(void *d, int c, size_t n)
{
    volatile u8 *dd = d;
    while (n--) *dd++ = (u8)c;
    return d;
}

VIS int memcmp(const void *a, const void *b, size_t n)
{
    const u8 *x = a, *y = b;
    for (; n; n--, x++, y++) if (*x != *y) return *x - *y;
    return 0;
}

static size_t slen(const char *s)
{
    size_t n = 0;
    while (s[n]) n++;
    return n;
}

static int sprefix(const char *s, const char *p)
{
    while (*p) if (*s++ != *p++) return 0;
    return 1;
}

static void scopy(char *d, const char *s, size_t cap)
{
    size_t i = 0;
    while (s[i] && i + 1 < cap) { d[i] = s[i]; i++; }
    d[i] = 0;
}

static void die(const char *msg, const char *arg, long err)
{
    char buf[512];
    size_t n = 0;
    const char *parts[3] = {"winloader: ", msg, arg};
    for (int i = 0; i < 3; i++) {
        if (!parts[i]) continue;
        for (size_t j = 0; parts[i][j] && n < sizeof(buf) - 24; j++) buf[n++] = parts[i][j];
    }
    if (err) {
        buf[n++] = ' '; buf[n++] = '('; buf[n++] = 'e'; buf[n++] = '=';
        char tmp[8]; int t = 0;
        long e = err < 0 ? -err : err;
        do { tmp[t++] = '0' + e % 10; e /= 10; } while (e);
        while (t) buf[n++] = tmp[--t];
        buf[n++] = ')';
    }
    buf[n++] = '\n';
    sc(NR_write, 2, (long)buf, n, 0, 0, 0);
    sc(NR_exit_group, 127, 0, 0, 0, 0, 0);
    for (;;) {}
}

static u64 g_pagesz = 4096;
static char g_self[512];

static u64 align_down(u64 v) { return v & ~(g_pagesz - 1); }
static u64 align_up(u64 v) { return (v + g_pagesz - 1) & ~(g_pagesz - 1); }


struct ehdr {
    u8 ident[16];
    u16 type, machine;
    u32 version;
    u64 entry, phoff, shoff;
    u32 flags;
    u16 ehsize, phentsize, phnum, shentsize, shnum, shstrndx;
};

struct phdr {
    u32 type, flags;
    u64 offset, vaddr, paddr, filesz, memsz, align;
};

#define PT_LOAD 1
#define PT_INTERP 3
#define PT_PHDR 6
#define ET_EXEC 2
#define ET_DYN 3
#define EM_AARCH64 183

struct elfinfo {
    u64 entry;
    u64 phdr_addr;
    u64 base;
    u16 phnum;
    char interp[256];
    int has_interp;
};

static long map_flags_to_prot(u32 f)
{
    return ((f & 4) ? PROT_READ : 0) | ((f & 2) ? PROT_WRITE : 0) | ((f & 1) ? PROT_EXEC : 0);
}

/* Maps an ET_EXEC/ET_DYN aarch64 image. Returns 0 or -errno (-ENOEXEC: not a loadable aarch64 ELF). */
static long load_elf(const char *path, struct elfinfo *o)
{
    struct ehdr eh;
    struct phdr ph[64];

    long fd = sc(NR_openat, AT_FDCWD, (long)path, O_CLOEXEC, 0, 0, 0);
    if (is_err(fd)) return fd;

    long r = sc(NR_pread64, fd, (long)&eh, sizeof(eh), 0, 0, 0);
    if (r != (long)sizeof(eh) || memcmp(eh.ident, "\x7f" "ELF", 4) != 0 || eh.ident[4] != 2 || eh.ident[5] != 1 ||
        eh.machine != EM_AARCH64 || (eh.type != ET_EXEC && eh.type != ET_DYN)) {
        sc(NR_close, fd, 0, 0, 0, 0, 0);
        return -ENOEXEC;
    }
    if (eh.phentsize != sizeof(struct phdr) || eh.phnum == 0 || eh.phnum > 64) {
        sc(NR_close, fd, 0, 0, 0, 0, 0);
        return -ENOEXEC;
    }
    r = sc(NR_pread64, fd, (long)ph, eh.phnum * sizeof(struct phdr), eh.phoff, 0, 0);
    if (r != (long)(eh.phnum * sizeof(struct phdr))) {
        sc(NR_close, fd, 0, 0, 0, 0, 0);
        return -ENOEXEC;
    }

    u64 lo = ~0UL, hi = 0;
    for (int i = 0; i < eh.phnum; i++) {
        if (ph[i].type != PT_LOAD) continue;
        if (ph[i].vaddr < lo) lo = ph[i].vaddr;
        if (ph[i].vaddr + ph[i].memsz > hi) hi = ph[i].vaddr + ph[i].memsz;
    }
    if (lo == ~0UL) { sc(NR_close, fd, 0, 0, 0, 0, 0); return -ENOEXEC; }
    lo = align_down(lo);
    hi = align_up(hi);

    u64 bias = 0;
    long map_extra = MAP_FIXED;
    if (eh.type == ET_DYN) {
        long res = sc(NR_mmap, 0, hi - lo, 0, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
        if (is_err(res)) { sc(NR_close, fd, 0, 0, 0, 0, 0); return res; }
        bias = (u64)res - lo;
    } else {
        map_extra = MAP_FIXED_NOREPLACE;
    }

    o->has_interp = 0;
    o->phdr_addr = 0;
    o->phnum = eh.phnum;
    o->base = bias;
    o->entry = eh.entry + bias;

    for (int i = 0; i < eh.phnum; i++) {
        struct phdr *p = &ph[i];
        if (p->type == PT_INTERP && p->filesz > 1 && p->filesz < sizeof(o->interp)) {
            r = sc(NR_pread64, fd, (long)o->interp, p->filesz, p->offset, 0, 0);
            if (r != (long)p->filesz) { sc(NR_close, fd, 0, 0, 0, 0, 0); return -ENOEXEC; }
            o->interp[p->filesz] = 0;
            o->has_interp = 1;
        } else if (p->type == PT_PHDR) {
            o->phdr_addr = p->vaddr + bias;
        } else if (p->type == PT_LOAD) {
            u64 vaddr = p->vaddr + bias;
            u64 seg = align_down(vaddr);
            long prot = map_flags_to_prot(p->flags);

            if (p->filesz) {
                u64 len = align_up(vaddr + p->filesz) - seg;
                long m = sc(NR_mmap, seg, len, prot, MAP_PRIVATE | map_extra, fd, align_down(p->offset));
                if (is_err(m) || (u64)m != seg) { sc(NR_close, fd, 0, 0, 0, 0, 0); return is_err(m) ? m : -ENOMEM; }
            }
            if (p->memsz > p->filesz) {
                u64 zero = vaddr + p->filesz;
                u64 page_end = align_up(zero);
                if ((prot & PROT_WRITE) && p->filesz && zero < page_end) memset((void *)zero, 0, page_end - zero);
                u64 bss_end = align_up(vaddr + p->memsz);
                u64 anon_start = p->filesz ? page_end : seg;
                if (bss_end > anon_start) {
                    long m = sc(NR_mmap, anon_start, bss_end - anon_start, prot, MAP_PRIVATE | map_extra | MAP_ANONYMOUS, -1, 0);
                    if (is_err(m) || (u64)m != anon_start) { sc(NR_close, fd, 0, 0, 0, 0, 0); return is_err(m) ? m : -ENOMEM; }
                }
            }
        }
    }

    if (!o->phdr_addr) {
        for (int i = 0; i < eh.phnum; i++) {
            if (ph[i].type == PT_LOAD && eh.phoff >= ph[i].offset && eh.phoff < ph[i].offset + ph[i].filesz) {
                o->phdr_addr = ph[i].vaddr + (eh.phoff - ph[i].offset) + bias;
                break;
            }
        }
    }
    sc(NR_close, fd, 0, 0, 0, 0, 0);
    return o->phdr_addr ? 0 : -ENOEXEC;
}


__asm__(
    ".text\n"
    ".globl _start\n"
    ".hidden _start\n"
    ".type _start,%function\n"
    "_start:\n"
    "  mov x0, sp\n"
    "  bl loader_main\n"
    "  brk #0\n"
    ".globl winloader_jump\n"
    ".hidden winloader_jump\n"
    ".type winloader_jump,%function\n"
    "winloader_jump:\n"
    "  mov x9, x0\n"
    "1: cbz x2, 2f\n"
    "  ldp x4, x5, [x1], #16\n"
    "  stp x4, x5, [x9], #16\n"
    "  sub x2, x2, #16\n"
    "  b 1b\n"
    "2: mov sp, x0\n"
    "  mov x9, x3\n"
    "  mov x0, xzr\n  mov x1, xzr\n  mov x2, xzr\n  mov x3, xzr\n  mov x4, xzr\n  mov x5, xzr\n"
    "  mov x6, xzr\n  mov x7, xzr\n  mov x8, xzr\n  mov x10, xzr\n  mov x11, xzr\n  mov x12, xzr\n"
    "  mov x13, xzr\n  mov x14, xzr\n  mov x15, xzr\n  mov x16, xzr\n  mov x17, xzr\n  mov x18, xzr\n"
    "  mov x19, xzr\n  mov x20, xzr\n  mov x21, xzr\n  mov x22, xzr\n  mov x23, xzr\n  mov x24, xzr\n"
    "  mov x25, xzr\n  mov x26, xzr\n  mov x27, xzr\n  mov x28, xzr\n  mov x29, xzr\n  mov x30, xzr\n"
    "  br x9\n"
);

VIS void winloader_jump(void *dst, const void *src, size_t n, u64 entry) __attribute__((noreturn));
VIS void loader_main(long *sp) __attribute__((noreturn));

struct argvec {
    const char *pre[8];
    int npre;
    char **tail; /* original argv entries appended after pre[] */
    int ntail;
};

static const char *av_get(const struct argvec *a, int i) { return i < a->npre ? a->pre[i] : a->tail[i - a->npre]; }
static int av_count(const struct argvec *a) { return a->npre + a->ntail; }

static int seq(const char *a, const char *b)
{
    while (*a && *a == *b) { a++; b++; }
    return *a == *b;
}

static const char *env_get(char **envp, const char *name)
{
    size_t n = slen(name);
    for (; *envp; envp++) if (sprefix(*envp, name) && (*envp)[n] == '=') return *envp + n + 1;
    return NULL;
}

static const char *base_name(const char *p)
{
    const char *b = p;
    for (; *p; p++) if (*p == '/') b = p + 1;
    return b;
}

VIS void loader_main(long *sp)
{
    long argc = sp[0];
    char **argv = (char **)(sp + 1);
    char **envp = argv + argc + 1;
    u64 *auxv = (u64 *)envp;
    while (*auxv) auxv++;
    auxv++;
    int envc = 0;
    while (envp[envc]) envc++;

    for (u64 *a = auxv; a[0] != AUXV_NULL; a += 2)
        if (a[0] == AUXV_PAGESZ) g_pagesz = a[1];

    long n = sc(NR_readlinkat, AT_FDCWD, (long)"/proc/self/exe", (long)g_self, sizeof(g_self) - 1, 0, 0);
    if (is_err(n) || n <= 0) die("cannot resolve /proc/self/exe", NULL, n);
    g_self[n] = 0;

    int ai = 1;
    const char *argv0 = NULL;
    while (ai < argc && argv[ai][0] == '-' && argv[ai][1]) {
        if (slen(argv[ai]) == 2 && argv[ai][1] == 'a' && ai + 1 < argc) { argv0 = argv[ai + 1]; ai += 2; }
        else if (slen(argv[ai]) == 2 && argv[ai][1] == '-') { ai++; break; }
        else die("unknown option ", argv[ai], 0);
    }
    if (ai >= argc) die("usage: libwinloader.so [-a argv0] <program> [args...]", NULL, 0);

    const char *fallback = env_get(envp, "WINLOADER_FALLBACK");

    struct argvec av;
    av.npre = 0;
    av.pre[av.npre++] = argv0 ? argv0 : argv[ai];
    const char *target = argv[ai];
    av.tail = argv + ai + 1;
    av.ntail = (int)argc - ai - 1;

    struct elfinfo main_img, interp_img;
    long r = 0;
    int tries = 0;

    for (;;) {
        if (++tries > 6) die("too many levels of interpreters for ", target, 0);

        char head[272];
        long fd = sc(NR_openat, AT_FDCWD, (long)target, O_CLOEXEC, 0, 0, 0);
        if (is_err(fd)) die("cannot open ", target, fd);
        long got = sc(NR_pread64, fd, (long)head, sizeof(head) - 1, 0, 0, 0);
        sc(NR_close, fd, 0, 0, 0, 0, 0);
        if (got >= 2 && head[0] == '#' && head[1] == '!') {
            head[got] = 0;
            char *e = head + 2;
            while (*e && *e != '\n') e++;
            *e = 0;
            char *interp = head + 2;
            while (*interp == ' ' || *interp == '\t') interp++;
            char *arg = interp;
            while (*arg && *arg != ' ' && *arg != '\t') arg++;
            if (*arg) { *arg++ = 0; while (*arg == ' ' || *arg == '\t') arg++; }
            if (!*interp) die("bad interpreter line in ", target, 0);

            /* "#!/bin/sh" does not exist on Android */
            static char interp_buf[300], arg_buf[272], script_buf[512];
            scopy(interp_buf, interp, sizeof(interp_buf));
            long ok = sc(NR_openat, AT_FDCWD, (long)interp_buf, O_CLOEXEC, 0, 0, 0);
            if (is_err(ok)) {
                scopy(interp_buf, "/system/bin/", sizeof(interp_buf));
                scopy(interp_buf + slen(interp_buf), base_name(interp), sizeof(interp_buf) - 12);
            } else sc(NR_close, ok, 0, 0, 0, 0, 0);
            scopy(arg_buf, arg, sizeof(arg_buf));
            scopy(script_buf, target, sizeof(script_buf));

            struct argvec nv;
            nv.npre = 0;
            nv.pre[nv.npre++] = interp_buf;
            if (*arg_buf) nv.pre[nv.npre++] = arg_buf;
            nv.pre[nv.npre++] = script_buf;
            for (int i = 1; i < av.npre && nv.npre < 8; i++) nv.pre[nv.npre++] = av.pre[i];
            nv.tail = av.tail;
            nv.ntail = av.ntail;
            av = nv;
            target = interp_buf;
            continue;
        }

        r = load_elf(target, &main_img);
        if (r == -ENOEXEC && fallback && !seq(target, fallback)) {
            static char target_copy[512];
            scopy(target_copy, target, sizeof(target_copy));
            struct argvec nv;
            nv.npre = 0;
            nv.pre[nv.npre++] = base_name(fallback);
            nv.pre[nv.npre++] = target_copy;
            for (int i = 1; i < av.npre && nv.npre < 8; i++) nv.pre[nv.npre++] = av.pre[i];
            nv.tail = av.tail;
            nv.ntail = av.ntail;
            av = nv;
            target = fallback;
            continue;
        }
        break;
    }
    if (r) die(r == -ENOEXEC ? "not an executable aarch64 ELF: " : "failed to load ", target, r);

    u64 entry = main_img.entry;
    u64 at_base = 0;
    if (main_img.has_interp) {
        r = load_elf(main_img.interp, &interp_img);
        if (r) die("failed to load interpreter ", main_img.interp, r);
        entry = interp_img.entry;
        at_base = interp_img.base;
    }

    /*
     * bionic's linker/libc replace the SIGSYS handler and use mprotect(PROT_EXEC) while starting up,
     * so exec interception can't be done with seccomp: LD_PRELOAD a shim that interposes libc instead.
     */
    int bionic = main_img.has_interp && (sprefix(main_img.interp, "/system/bin/linker") || sprefix(main_img.interp, "/apex/"));
    int shim_env = bionic && !env_get(envp, "WINLOADER_NOSECCOMP");

    static char ev_path[sizeof("WINLOADER_PATH=") + sizeof(g_self)];
    static char ev_exe[sizeof("WINLOADER_EXE=") + 512];
    static char ev_preload[sizeof("LD_PRELOAD=") + 2 * sizeof(g_self)];

    long envmem = sc(NR_mmap, 0, align_up((size_t)(envc + 4) * sizeof(char *)), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (is_err(envmem)) die("out of memory", NULL, envmem);
    char **nenvp = (char **)envmem;
    int nenv = 0;
    const char *old_preload = NULL;
    for (int i = 0; i < envc; i++) {
        if (shim_env && sprefix(envp[i], "LD_PRELOAD=")) { old_preload = envp[i] + 11; continue; }
        if (shim_env && (sprefix(envp[i], "WINLOADER_PATH=") || sprefix(envp[i], "WINLOADER_EXE="))) continue;
        nenvp[nenv++] = envp[i];
    }
    if (shim_env) {
        scopy(ev_path, "WINLOADER_PATH=", sizeof(ev_path));
        scopy(ev_path + slen(ev_path), g_self, sizeof(g_self));
        size_t dir = slen(g_self);
        while (dir && g_self[dir - 1] != '/') dir--;
        scopy(ev_preload, "LD_PRELOAD=", sizeof(ev_preload));
        memcpy(ev_preload + slen(ev_preload), g_self, dir);
        scopy(ev_preload + slen(ev_preload), "libwinloader_shim.so", 24);
        if (old_preload && *old_preload) {
            scopy(ev_preload + slen(ev_preload), ":", 2);
            scopy(ev_preload + slen(ev_preload), old_preload, sizeof(ev_preload) - slen(ev_preload));
        }
        nenvp[nenv++] = ev_path;

        /* /proc/self/exe points at the loader: tell the shim what the program really is (wine derives its dirs from it) */
        scopy(ev_exe, "WINLOADER_EXE=", sizeof(ev_exe));
        size_t el = slen(ev_exe);
        if (target[0] != '/') {
            long n = sc(NR_getcwd, (long)(ev_exe + el), (long)(sizeof(ev_exe) - el - 2), 0, 0, 0, 0);
            if (!is_err(n) && n > 1) { el += (size_t)n - 1; ev_exe[el++] = '/'; }
        }
        scopy(ev_exe + el, target, sizeof(ev_exe) - el);
        nenvp[nenv++] = ev_exe;
        nenvp[nenv++] = ev_preload;
    }
    nenvp[nenv] = NULL;
    envp = nenvp;
    envc = nenv;

    int nargc = av_count(&av);

    int naux = 0;
    for (u64 *a = auxv; a[0] != AUXV_NULL; a += 2) naux++;
    int max_aux = naux + 8;

    size_t str_sz = slen(target) + 1;
    for (int i = 0; i < nargc; i++) str_sz += slen(av_get(&av, i)) + 1;
    for (int i = 0; i < envc; i++) str_sz += slen(envp[i]) + 1;

    size_t ptr_sz = 8 + 8 * (size_t)(nargc + 1) + 8 * (size_t)(envc + 1) + 16 * (size_t)(max_aux + 1);
    size_t total = (ptr_sz + str_sz + 15) & ~15UL;

    long scratch = sc(NR_mmap, 0, align_up(total), PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (is_err(scratch)) die("out of memory", NULL, scratch);

    u64 dst = ((u64)sp - total - 512) & ~15UL;
    u64 *w = (u64 *)scratch;
    char *sbase = (char *)scratch + ptr_sz;
    u64 sdst = dst + ptr_sz;
    size_t soff = 0;

#define PUT_STR(s, out) do { size_t l_ = slen(s) + 1; memcpy(sbase + soff, (s), l_); (out) = sdst + soff; soff += l_; } while (0)

    *w++ = (u64)nargc;
    for (int i = 0; i < nargc; i++) { u64 p; PUT_STR(av_get(&av, i), p); *w++ = p; }
    *w++ = 0;
    for (int i = 0; i < envc; i++) { u64 p; PUT_STR(envp[i], p); *w++ = p; }
    *w++ = 0;

    u64 execfn;
    PUT_STR(target, execfn);

    for (u64 *a = auxv; a[0] != AUXV_NULL; a += 2) {
        switch (a[0]) {
            case AUXV_PHDR: case AUXV_PHENT: case AUXV_PHNUM: case AUXV_BASE:
            case AUXV_FLAGS: case AUXV_ENTRY: case AUXV_EXECFN:
                break;
            default:
                *w++ = a[0]; *w++ = a[1];
        }
    }
    *w++ = AUXV_PHDR; *w++ = main_img.phdr_addr;
    *w++ = AUXV_PHENT; *w++ = sizeof(struct phdr);
    *w++ = AUXV_PHNUM; *w++ = main_img.phnum;
    *w++ = AUXV_BASE; *w++ = at_base;
    *w++ = AUXV_FLAGS; *w++ = 0;
    *w++ = AUXV_ENTRY; *w++ = main_img.entry;
    *w++ = AUXV_EXECFN; *w++ = execfn;
    *w++ = AUXV_NULL; *w++ = 0;

    winloader_jump((void *)dst, (void *)scratch, total, entry);
}
