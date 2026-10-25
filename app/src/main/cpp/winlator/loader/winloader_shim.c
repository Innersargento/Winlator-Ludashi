/*
 * libwinloader_shim.so - LD_PRELOADed by winloader into bionic programs.
 *
 * Interposes libc to work around what SELinux denies for targetSdk >= 29:
 *  1. exec*() / posix_spawn*() are rewritten to "libwinloader.so -a argv0 <path> args".
 *  2. mprotect(PROT_EXEC) on written private file pages (execmod, hit by wine when
 *     relocating PE images) swaps the pages for an anonymous copy and retries.
 *  3. readlink("/proc/self/exe") / realpath answer WINLOADER_EXE, the real program.
 *
 * Needs WINLOADER_PATH (set by the loader). WINLOADER_NOSECCOMP disables everything.
 * Raw syscalls that bypass libc are not covered.
 */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <spawn.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/syscall.h>
#include <unistd.h>

#define EXPORT __attribute__((visibility("default")))

extern char **environ;

static const char *g_loader;
static size_t g_loader_dir;
static int g_off;
static const char *g_exe;

__attribute__((constructor)) static void shim_init(void)
{
    g_off = getenv("WINLOADER_NOSECCOMP") != NULL;
    g_loader = getenv("WINLOADER_PATH");
    g_exe = g_off ? NULL : getenv("WINLOADER_EXE");
    if (g_loader) {
        const char *slash = strrchr(g_loader, '/');
        g_loader_dir = slash ? (size_t)(slash - g_loader) + 1 : 0;
    }
}

static int active(void) { return !g_off && g_loader; }

static int has_prefix(const char *s, const char *p) { return strncmp(s, p, strlen(p)) == 0; }

static int needs_loader(const char *path)
{
    if (!active() || !path || !*path) return 0;
    if (has_prefix(path, "/system/") || has_prefix(path, "/system_ext/") || has_prefix(path, "/apex/") ||
        has_prefix(path, "/vendor/") || has_prefix(path, "/product/") || has_prefix(path, "/odm/") ||
        has_prefix(path, "/linkerconfig/"))
        return 0;
    if (g_loader_dir && strncmp(path, g_loader, g_loader_dir) == 0) return 0;

    int fd = open(path, O_RDONLY | O_CLOEXEC);
    if (fd < 0) return 0;
    char magic[4] = {0};
    ssize_t n = read(fd, magic, sizeof(magic));
    close(fd);
    return (n >= 4 && memcmp(magic, "\x7f" "ELF", 4) == 0) || (n >= 2 && magic[0] == '#' && magic[1] == '!');
}

static char **wrap_argv(const char *path, char *const argv[])
{
    int argc = 0;
    if (argv) while (argv[argc]) argc++;
    char **nv = malloc(sizeof(char *) * (size_t)(argc + 6));
    if (!nv) return NULL;
    int k = 0;
    nv[k++] = (char *)g_loader;
    if (argc > 0) { nv[k++] = "-a"; nv[k++] = argv[0]; }
    nv[k++] = (char *)path;
    for (int i = 1; i < argc; i++) nv[k++] = argv[i];
    nv[k] = NULL;
    return nv;
}

static int raw_execve(const char *path, char *const argv[], char *const envp[])
{
    return (int)syscall(__NR_execve, path, argv, envp);
}

EXPORT int execve(const char *path, char *const argv[], char *const envp[])
{
    if (needs_loader(path)) {
        char **nv = wrap_argv(path, argv);
        if (!nv) { errno = ENOMEM; return -1; }
        int saved;
        raw_execve(g_loader, nv, envp);
        saved = errno;
        free(nv);
        errno = saved;
        return -1;
    }
    return raw_execve(path, argv, envp);
}

EXPORT int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }

EXPORT int fexecve(int fd, char *const argv[], char *const envp[])
{
    char link[48], path[PATH_MAX];
    snprintf(link, sizeof(link), "/proc/self/fd/%d", fd);
    ssize_t n = readlink(link, path, sizeof(path) - 1);
    if (n <= 0) { errno = EBADF; return -1; }
    path[n] = 0;
    return execve(path, argv, envp);
}

/* PATH search shared by execvpe and posix_spawnp: calls try(path) until it does not fail with ENOENT/EACCES */
static const char *search_path(void)
{
    const char *p = getenv("PATH");
    return p ? p : "/system/bin:/system/xbin";
}

EXPORT int execvpe(const char *file, char *const argv[], char *const envp[])
{
    if (!file || !*file) { errno = ENOENT; return -1; }
    if (strchr(file, '/')) return execve(file, argv, envp);

    const char *p = search_path();
    size_t flen = strlen(file);
    int eacces = 0;
    while (1) {
        const char *end = strchr(p, ':');
        size_t dlen = end ? (size_t)(end - p) : strlen(p);
        char buf[PATH_MAX];
        if (dlen + 1 + flen < sizeof(buf)) {
            memcpy(buf, p, dlen);
            if (dlen) buf[dlen++] = '/';
            memcpy(buf + dlen, file, flen + 1);
            execve(buf, argv, envp);
            if (errno == EACCES) eacces = 1;
            else if (errno != ENOENT && errno != ENOTDIR) return -1;
        }
        if (!end) break;
        p = end + 1;
    }
    errno = eacces ? EACCES : ENOENT;
    return -1;
}

EXPORT int execvp(const char *file, char *const argv[]) { return execvpe(file, argv, environ); }

static char **collect_args(const char *arg0, va_list ap, int *count)
{
    int cap = 8, n = 0;
    char **v = malloc(sizeof(char *) * (size_t)cap);
    if (!v) return NULL;
    const char *a = arg0;
    while (1) {
        if (n + 2 > cap) {
            cap *= 2;
            char **nv = realloc(v, sizeof(char *) * (size_t)cap);
            if (!nv) { free(v); return NULL; }
            v = nv;
        }
        v[n++] = (char *)a;
        if (!a) break;
        a = va_arg(ap, const char *);
    }
    *count = n;
    return v;
}

EXPORT int execl(const char *path, const char *arg0, ...)
{
    va_list ap; int n;
    va_start(ap, arg0);
    char **v = collect_args(arg0, ap, &n);
    va_end(ap);
    if (!v) { errno = ENOMEM; return -1; }
    execve(path, v, environ);
    int e = errno; free(v); errno = e;
    return -1;
}

EXPORT int execlp(const char *file, const char *arg0, ...)
{
    va_list ap; int n;
    va_start(ap, arg0);
    char **v = collect_args(arg0, ap, &n);
    va_end(ap);
    if (!v) { errno = ENOMEM; return -1; }
    execvpe(file, v, environ);
    int e = errno; free(v); errno = e;
    return -1;
}

EXPORT int execle(const char *path, const char *arg0, ...)
{
    va_list ap; int n;
    va_start(ap, arg0);
    char **v = collect_args(arg0, ap, &n);
    char *const *envp = va_arg(ap, char *const *);
    va_end(ap);
    if (!v) { errno = ENOMEM; return -1; }
    execve(path, v, envp);
    int e = errno; free(v); errno = e;
    return -1;
}

typedef int (*spawn_fn)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                        char *const[], char *const[]);

static int spawn_wrapped(const char *name, pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                         const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    spawn_fn real = (spawn_fn)dlsym(RTLD_NEXT, "posix_spawn");
    if (!real) return ENOSYS;
    (void)name;
    if (needs_loader(path)) {
        char **nv = wrap_argv(path, argv);
        if (!nv) return ENOMEM;
        int r = real(pid, g_loader, fa, attr, nv, envp);
        free(nv);
        return r;
    }
    return real(pid, path, fa, attr, argv, envp);
}

EXPORT int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *fa,
                       const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    return spawn_wrapped("posix_spawn", pid, path, fa, attr, argv, envp);
}

EXPORT int posix_spawnp(pid_t *pid, const char *file, const posix_spawn_file_actions_t *fa,
                        const posix_spawnattr_t *attr, char *const argv[], char *const envp[])
{
    if (strchr(file, '/')) return spawn_wrapped("posix_spawnp", pid, file, fa, attr, argv, envp);

    const char *p = search_path();
    size_t flen = strlen(file);
    int last = ENOENT;
    while (1) {
        const char *end = strchr(p, ':');
        size_t dlen = end ? (size_t)(end - p) : strlen(p);
        char buf[PATH_MAX];
        if (dlen + 1 + flen < sizeof(buf)) {
            memcpy(buf, p, dlen);
            if (dlen) buf[dlen++] = '/';
            memcpy(buf + dlen, file, flen + 1);
            if (access(buf, X_OK) == 0 || needs_loader(buf)) return spawn_wrapped("posix_spawnp", pid, buf, fa, attr, argv, envp);
            if (errno == EACCES) last = EACCES;
        }
        if (!end) break;
        p = end + 1;
    }
    return last;
}


static const char *fix_self_exe(const char *path)
{
    return (g_exe && path && strcmp(path, "/proc/self/exe") == 0) ? g_exe : NULL;
}

static ssize_t copy_exe(const char *exe, char *buf, size_t size)
{
    size_t n = strlen(exe);
    if (n > size) n = size;
    memcpy(buf, exe, n); /* readlink() does not NUL-terminate */
    return (ssize_t)n;
}

EXPORT ssize_t readlink(const char *path, char *buf, size_t size)
{
    const char *exe = fix_self_exe(path);
    if (exe) return copy_exe(exe, buf, size);
    return (ssize_t)syscall(__NR_readlinkat, AT_FDCWD, path, buf, size);
}

EXPORT ssize_t readlinkat(int dirfd, const char *path, char *buf, size_t size)
{
    const char *exe = fix_self_exe(path);
    if (exe && (path[0] == '/' || dirfd == AT_FDCWD)) return copy_exe(exe, buf, size);
    return (ssize_t)syscall(__NR_readlinkat, dirfd, path, buf, size);
}

EXPORT char *realpath(const char *path, char *resolved)
{
    typedef char *(*realpath_fn)(const char *, char *);
    static realpath_fn real;
    if (!real) real = (realpath_fn)dlsym(RTLD_NEXT, "realpath");
    const char *exe = fix_self_exe(path);
    return real(exe ? exe : path, resolved);
}


static int raw_mprotect(void *addr, size_t len, int prot) { return (int)syscall(__NR_mprotect, addr, len, prot); }

/* execmod only happens on private file mappings: anonymous memory (e.g. [stack]) is denied for other reasons and
 * swapping it would clobber pages in use, including the frames of this very call */
static int file_backed(const void *addr)
{
    FILE *f = fopen("/proc/self/maps", "r");
    if (!f) return 1;
    char line[512];
    int res = 1;
    while (fgets(line, sizeof(line), f)) {
        unsigned long lo, hi, ino = 1;
        char perms[8];
        if (sscanf(line, "%lx-%lx %7s %*s %*s %lu", &lo, &hi, perms, &ino) >= 3 &&
            (unsigned long)addr >= lo && (unsigned long)addr < hi) {
            res = ino != 0;
            break;
        }
    }
    fclose(f);
    return res;
}

static int make_anon_exec(char *addr, size_t len, int prot)
{
    void *tmp = mmap(NULL, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (tmp == MAP_FAILED) return -1;
    int r = -1;
    /* keep PROT_WRITE: other threads may still be writing to the page, dropping it makes them SIGSEGV */
    if (raw_mprotect(addr, len, PROT_READ | PROT_WRITE) == 0) {
        memcpy(tmp, addr, len);
        if (mmap(addr, len, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS | MAP_FIXED, -1, 0) != MAP_FAILED) {
            memcpy(addr, tmp, len);
            r = raw_mprotect(addr, len, prot);
        }
    }
    int e = errno;
    munmap(tmp, len);
    errno = e;
    return r;
}

EXPORT int mprotect(void *addr, size_t len, int prot)
{
    int r = raw_mprotect(addr, len, prot);
    if (r == 0 || errno != EACCES || !(prot & PROT_EXEC) || !active()) return r;

    long pg = sysconf(_SC_PAGESIZE);
    if ((uintptr_t)addr & (uintptr_t)(pg - 1)) return r;
    size_t total = (len + (size_t)pg - 1) & ~((size_t)pg - 1);
    const size_t chunk = 16 * 4096;
    for (size_t off = 0; off < total; off += chunk) {
        size_t n = total - off < chunk ? total - off : chunk;
        char *p = (char *)addr + off;
        if (raw_mprotect(p, n, prot) == 0) continue;
        if (errno != EACCES) return -1;
        if (!file_backed(p)) { errno = EACCES; return -1; }
        if (make_anon_exec(p, n, prot) != 0) return -1;
    }
    return 0;
}
