/* Bionic Wine/Box64 loader bridge. This is not a security sandbox. */
#define _GNU_SOURCE
#include <dlfcn.h>
#include <errno.h>
#include <stdio.h>
#include <spawn.h>
#include <stdlib.h>
#include <string.h>
#include <sys/syscall.h>
#include <sys/mman.h>
#include <unistd.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <stddef.h>

extern char **environ;
static const char *linker = "/system/bin/linker64";

int connect(int fd, const struct sockaddr *address, socklen_t length) {
    int (*real)(int, const struct sockaddr *, socklen_t) = dlsym(RTLD_NEXT, "connect");
    const char *socket = getenv("ASTRA_X11_SOCKET");
    if (socket && address && address->sa_family == AF_UNIX && length > offsetof(struct sockaddr_un, sun_path)) {
        const struct sockaddr_un *un = (const struct sockaddr_un *)address;
        size_t count = length - offsetof(struct sockaddr_un, sun_path);
        if (count > sizeof(un->sun_path)) count = sizeof(un->sun_path);
        if (memmem(un->sun_path, count, ".X11-unix/X0", 12)) {
            struct sockaddr_un target = { .sun_family = AF_UNIX };
            if (strlen(socket) >= sizeof(target.sun_path)) { errno = ENAMETOOLONG; return -1; }
            strcpy(target.sun_path, socket);
            return real(fd, (const struct sockaddr *)&target, offsetof(struct sockaddr_un, sun_path) + strlen(socket) + 1);
        }
    }
    return real(fd, address, length);
}

int mprotect(void *address, size_t length, int protection) {
    int (*real)(void *, size_t, int) = dlsym(RTLD_NEXT, "mprotect");
    int result = real(address, length, protection);
    if (result == 0 || !(protection & PROT_EXEC) || errno != EACCES) return result;
    // PE relocations dirty private file mappings. Android disallows executable
    // dirty file pages; preserve the bytes in an anonymous mapping instead.
    void *copy = mmap(NULL, length, PROT_READ | PROT_WRITE, MAP_PRIVATE | MAP_ANONYMOUS, -1, 0);
    if (copy == MAP_FAILED) return -1;
    memcpy(copy, address, length);
    if (real(copy, length, protection) != 0) { int e = errno; munmap(copy, length); errno = e; return -1; }
    if (mremap(copy, length, length, MREMAP_MAYMOVE | MREMAP_FIXED, address) == MAP_FAILED) {
        int e = errno; munmap(copy, length); errno = e; return -1;
    }
    return 0;
}

char *realpath(const char *path, char *resolved) {
    char *(*real)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
    const char *wine = getenv("ASTRA_WINE_LOADER");
    return real(wine && strcmp(path, "/proc/self/exe") == 0 ? wine : path, resolved);
}

static int applies(const char *path) {
    const char *root = getenv("ASTRA_RUNTIME_ROOT");
    if (!path || !root || !*root) return 0;
    char *(*resolve)(const char *, char *) = dlsym(RTLD_NEXT, "realpath");
    char *canonical_root = resolve(root, NULL);
    char *canonical_path = resolve(path, NULL);
    int matches = canonical_root && canonical_path && strncmp(canonical_path, canonical_root, strlen(canonical_root)) == 0
        && canonical_path[strlen(canonical_root)] == '/';
    if (matches) {
        unsigned char header[18];
        FILE *file = fopen(canonical_path, "rb");
        matches = file && fread(header, 1, sizeof(header), file) == sizeof(header)
            && !memcmp(header, "\177ELF", 4) && header[16] == 3 && header[17] == 0;
        if (file) fclose(file);
    }
    free(canonical_root);
    free(canonical_path);
    return matches;
}

static char **linker_args(const char *path, char *const argv[]) {
    size_t count = 0;
    while (argv && argv[count]) ++count;
    const char *box64 = NULL;
#if defined(__aarch64__)
    FILE *file = fopen(path, "rb");
    unsigned char header[20];
    if (file) {
        if (fread(header, 1, sizeof(header), file) == sizeof(header) && header[18] == 62)
            box64 = getenv("ASTRA_BOX64");
        fclose(file);
    }
#endif
    char **args = calloc(count + 4, sizeof(char *));
    if (!args) return NULL;
    args[0] = (char *)linker;
    size_t offset = box64 ? 2 : 1;
    if (box64) args[1] = (char *)box64;
    args[offset] = (char *)path;
    for (size_t i = 1; i < count; ++i) args[i + offset] = argv[i];
    return args;
}

int execve(const char *path, char *const argv[], char *const envp[]) {
    if (!applies(path)) return syscall(__NR_execve, path, argv, envp);
    char **args = linker_args(path, argv);
    if (!args) { errno = ENOMEM; return -1; }
    int result = syscall(__NR_execve, linker, args, envp);
    int error = errno;
    free(args);
    errno = error;
    return result;
}
int execv(const char *path, char *const argv[]) { return execve(path, argv, environ); }
int execvp(const char *path, char *const argv[]) {
    if (applies(path)) return execve(path, argv, environ);
    int (*real)(const char *, char *const[]) = dlsym(RTLD_NEXT, "execvp");
    return real(path, argv);
}
int execvpe(const char *path, char *const argv[], char *const envp[]) {
    if (applies(path)) return execve(path, argv, envp);
    int (*real)(const char *, char *const[], char *const[]) = dlsym(RTLD_NEXT, "execvpe");
    return real(path, argv, envp);
}
int posix_spawn(pid_t *pid, const char *path, const posix_spawn_file_actions_t *actions,
                const posix_spawnattr_t *attr, char *const argv[], char *const envp[]) {
    int (*real)(pid_t *, const char *, const posix_spawn_file_actions_t *, const posix_spawnattr_t *,
                char *const[], char *const[]) = dlsym(RTLD_NEXT, "posix_spawn");
    if (!applies(path)) return real(pid, path, actions, attr, argv, envp);
    char **args = linker_args(path, argv);
    if (!args) return ENOMEM;
    int result = real(pid, linker, actions, attr, args, envp);
    free(args);
    return result;
}

