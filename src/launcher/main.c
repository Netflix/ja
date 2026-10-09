/*
 * Copyright 2026 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */

/*
 * AOT-aware native launcher for Tool and ToolProvider commands.
 *
 * The executable name selects a Tool or ToolProvider command.
 * HotSpot AOT caches are stored in a user-writable platform cache directory.
 * OpenJ9 shared class caches are stored in the runtime image.
 *
 * Opted-in commands warm a VM-specific cache when none exists. Subsequent runs
 * use the cache for fast startup.
 */

#ifndef _WIN32
#define _GNU_SOURCE
#endif

#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <errno.h>
#include <ctype.h>
#include "jni.h"

#ifdef _WIN32
#include <io.h>
#include <direct.h>
#include <windows.h>
#define F_OK 0
#define access _access
#define chdir _chdir
#define mkdir(d, m) _mkdir(d)
#define unlink _unlink
#else
#include <dlfcn.h>
#include <fcntl.h>
#include <dirent.h>
#include <limits.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>
#endif

typedef int (*jli_launch_function)(int argc, char **argv,
        int jargc, const char **jargv,
        int appclassc, const char **appclassv,
        const char *fullversion, const char *dotversion,
        const char *pname, const char *lname,
        jboolean javaargs, jboolean cpwildcard,
        jboolean javaw, jint ergo_class);

static jli_launch_function jli_launch;

/* djb2 hash. */
static unsigned long djb2(unsigned long hash, const char *data, size_t len) {
    for (size_t i = 0; i < len; i++)
        hash = hash * 33 + (unsigned char)data[i];
    return hash;
}

static int hash_file_size(unsigned long *hash, const char *path) {
    unsigned long long file_size;
#ifdef _WIN32
    WIN32_FILE_ATTRIBUTE_DATA attributes;
    if (!GetFileAttributesExA(path, GetFileExInfoStandard, &attributes) ||
            (attributes.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY)) {
        return 0;
    }
    file_size = ((unsigned long long)attributes.nFileSizeHigh << 32) |
            attributes.nFileSizeLow;
#else
    struct stat st;
    if (stat(path, &st) != 0 || !S_ISREG(st.st_mode)) return 0;
    file_size = (unsigned long long)st.st_size;
#endif

    char value[32];
    int len = snprintf(value, sizeof(value), "%llu", file_size);
    if (len <= 0 || len >= (int)sizeof(value)) return 0;
    *hash = djb2(*hash, value, (size_t)len);
    return 1;
}

static int hash_file(unsigned long *hash, const char *path) {
    FILE *file = fopen(path, "rb");
    if (!file) return 0;
    unsigned char buffer[8192];
    size_t count;
    while ((count = fread(buffer, 1, sizeof(buffer), file)) > 0)
        *hash = djb2(*hash, (const char *)buffer, count);
    int result = !ferror(file);
    fclose(file);
    return result;
}

static int is_regular_file(const char *path) {
#ifdef _WIN32
    DWORD attributes = GetFileAttributesA(path);
    return attributes != INVALID_FILE_ATTRIBUTES &&
            !(attributes & FILE_ATTRIBUTE_DIRECTORY);
#else
    struct stat st;
    return stat(path, &st) == 0 && S_ISREG(st.st_mode);
#endif
}

static int create_file_exclusively(const char *path) {
#ifdef _WIN32
    HANDLE file = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_NEW,
            FILE_ATTRIBUTE_NORMAL, NULL);
    if (file == INVALID_HANDLE_VALUE) return 0;
    return CloseHandle(file);
#else
    int file = open(path, O_WRONLY | O_CREAT | O_EXCL, 0600);
    if (file < 0) return 0;
    return close(file) == 0;
#endif
}

#define MAX_RUNTIME_OPTIONS 256
#define MAX_RUNTIME_OPTION_LENGTH 4096

enum aot_mode {
    AOT_AUTO,
    AOT_CREATE,
    AOT_OFF,
    AOT_INVALID
};

static enum aot_mode parse_aot_mode(const char *value) {
    if (strcmp(value, "auto") == 0) return AOT_AUTO;
    if (strcmp(value, "create") == 0) return AOT_CREATE;
    if (strcmp(value, "off") == 0) return AOT_OFF;
    return AOT_INVALID;
}

static enum aot_mode parse_aot_option(const char *option) {
    const char *prefix = "-L-aot=";
    if (strncmp(option, prefix, strlen(prefix)) != 0) return AOT_INVALID;
    return parse_aot_mode(option + strlen(prefix));
}

static int valid_runtime_option(const char *option) {
    if (strcmp(option, "--enable-preview") == 0) return 1;
    const char *prefixes[] = {
        "--add-modules=",
        "--enable-native-access=",
        "--enable-final-field-mutation=",
        "--add-exports=",
        "--add-opens="
    };
    for (size_t i = 0; i < sizeof(prefixes) / sizeof(prefixes[0]); i++) {
        size_t length = strlen(prefixes[i]);
        if (strncmp(option, prefixes[i], length) == 0 && option[length]) {
            for (const char *character = option; *character; character++) {
                if (isspace((unsigned char)*character)) return 0;
            }
            return 1;
        }
    }
    return 0;
}

static int load_runtime_options(
        const char *tool,
        const char *path,
        char options[MAX_RUNTIME_OPTIONS][MAX_RUNTIME_OPTION_LENGTH],
        enum aot_mode *aot_mode,
        int explicit_aot_mode) {
    FILE *file = fopen(path, "r");
    if (!file) {
        fprintf(stderr, "%s: cannot read launcher runtime options: %s: %s\n",
                tool, path, strerror(errno));
        return -1;
    }
    int count = 0;
    int configured_aot_mode = 0;
    while (fgets(options[count], MAX_RUNTIME_OPTION_LENGTH, file)) {
        size_t length = strlen(options[count]);
        if (length > 0 && options[count][length - 1] != '\n' && !feof(file)) {
            fprintf(stderr, "%s: launcher runtime option is too long: %s\n", tool, path);
            fclose(file);
            return -1;
        }
        while (length > 0 &&
                (options[count][length - 1] == '\n' || options[count][length - 1] == '\r')) {
            options[count][--length] = '\0';
        }
        if (length == 0) continue;
        if (strncmp(options[count], "-L-aot=", 7) == 0) {
            enum aot_mode mode = parse_aot_option(options[count]);
            if (mode == AOT_INVALID) {
                fprintf(stderr, "%s: invalid launcher AOT mode in %s: %s\n",
                        tool, path, options[count]);
                fclose(file);
                return -1;
            }
            if (configured_aot_mode) {
                fprintf(stderr, "%s: duplicate launcher AOT mode in %s\n", tool, path);
                fclose(file);
                return -1;
            }
            configured_aot_mode = 1;
            if (!explicit_aot_mode) *aot_mode = mode;
            continue;
        }
        if (!valid_runtime_option(options[count])) {
            fprintf(stderr, "%s: invalid launcher runtime option in %s: %s\n",
                    tool, path, options[count]);
            fclose(file);
            return -1;
        }
        if (++count == MAX_RUNTIME_OPTIONS) {
            int extra = fgetc(file);
            if (extra != EOF) {
                fprintf(stderr, "%s: too many launcher runtime options: %s\n", tool, path);
                fclose(file);
                return -1;
            }
            break;
        }
    }
    int failed = ferror(file);
    fclose(file);
    if (failed) {
        fprintf(stderr, "%s: cannot read launcher runtime options: %s\n", tool, path);
        return -1;
    }
    return count;
}

static const char *tool_name(const char *argv0) {
    const char *name = argv0;
    for (const char *p = argv0; *p; p++) {
        if (*p == '/' || *p == '\\') name = p + 1;
    }
#ifdef _WIN32
    size_t length = strlen(name);
    if (length > 4 && _stricmp(name + length - 4, ".exe") == 0) {
        static char without_extension[4096];
        if (length - 4 >= sizeof(without_extension)) return NULL;
        memcpy(without_extension, name, length - 4);
        without_extension[length - 4] = '\0';
        return without_extension;
    }
#endif
    return *name ? name : NULL;
}

static int apply_launcher_options(
        const char *tool, int *argument_count, char ***argument_values,
        enum aot_mode *aot_mode, int *explicit_aot_mode) {
    int argc = *argument_count;
    char **argv = *argument_values;
    int first = 1;

    if (first < argc && strcmp(argv[first], "--") == 0) {
        first++;
    } else {
        while (first < argc) {
            if (strncmp(argv[first], "-L-aot=", 7) == 0) {
                enum aot_mode mode = parse_aot_option(argv[first]);
                if (mode == AOT_INVALID) {
                    fprintf(stderr,
                            "%s: invalid -L-aot mode; expected auto, create, or off\n",
                            tool);
                    return 0;
                }
                if (*explicit_aot_mode) {
                    fprintf(stderr, "%s: -L-aot may only be specified once\n", tool);
                    return 0;
                }
                *aot_mode = mode;
                *explicit_aot_mode = 1;
                first++;
                continue;
            }
            if (strncmp(argv[first], "-L", 2) == 0) {
                fprintf(stderr, "%s: unknown launcher option: %s\n", tool, argv[first]);
                return 0;
            }
            break;
        }
        if (first < argc && strcmp(argv[first], "--") == 0) first++;
    }

    if (first == 1) return 1;
    int retained = argc - first;
    for (int i = 0; i < retained; i++) argv[i + 1] = argv[first + i];
    argv[retained + 1] = NULL;
    *argument_count = retained + 1;
    return 1;
}

#ifndef _WIN32
static int resolve_path(const char *path, char *buf, size_t size) {
    char resolved[PATH_MAX];
    if (!realpath(path, resolved)) return 0;
    size_t len = strlen(resolved);
    if (len >= size) return 0;
    memcpy(buf, resolved, len + 1);
    return 1;
}

static int resolve_from_path(const char *program, char *buf, size_t size) {
    if (strchr(program, '/')) return resolve_path(program, buf, size);

    const char *path = getenv("PATH");
    if (!path || !*path) path = ".";

    const char *component = path;
    for (;;) {
        const char *end = strchr(component, ':');
        size_t component_len = end ? (size_t)(end - component) : strlen(component);
        const char *directory = component_len == 0 ? "." : component;
        int len;
        char candidate[PATH_MAX];
        if (component_len == 0) {
            len = snprintf(candidate, sizeof(candidate), "./%s", program);
        } else {
            len = snprintf(candidate, sizeof(candidate), "%.*s/%s",
                    (int)component_len, directory, program);
        }
        struct stat st;
        if (len > 0 && len < (int)sizeof(candidate) &&
                stat(candidate, &st) == 0 && !S_ISDIR(st.st_mode) &&
                access(candidate, X_OK) == 0 && resolve_path(candidate, buf, size)) {
            return 1;
        }
        if (!end) return 0;
        component = end + 1;
    }
}

static int get_executable_path(const char *argv0, char *buf, size_t size) {
#ifdef __linux__
    char path[PATH_MAX];
    ssize_t len = readlink("/proc/self/exe", path, sizeof(path) - 1);
    if (len >= 0) {
        path[len] = '\0';
        if (resolve_path(path, buf, size)) return 1;
        if ((size_t)len < size) {
            memcpy(buf, path, (size_t)len + 1);
            return 1;
        }
    }
#endif
    return argv0 && resolve_from_path(argv0, buf, size);
}
#else
static int get_executable_path(const char *argv0, char *buf, size_t size) {
    (void)argv0;
    DWORD len = GetModuleFileNameA(NULL, buf, (DWORD)size);
    return len > 0 && len < size;
}
#endif

static int get_launcher_home(const char *argv0, char *buf, size_t size) {
    if (!get_executable_path(argv0, buf, size)) return 0;

#ifdef _WIN32
    char *component = strrchr(buf, '\\');
    if (!component) return 0;
    *component = '\0';
    component = strrchr(buf, '\\');
    if (!component) return 0;
    *component = '\0';
#else
    char *bin = NULL;
    for (char *p = buf; (p = strstr(p, "/bin/")) != NULL; p += 5)
        bin = p;
    if (!bin) return 0;
    *bin = '\0';
#endif
    return 1;
}

static int is_runtime_home(const char *home) {
    char runtime[4096];
#ifdef _WIN32
    int len = snprintf(runtime, sizeof(runtime), "%s\\bin\\java.dll", home);
#elif defined(__APPLE__)
    int len = snprintf(runtime, sizeof(runtime), "%s/lib/libjava.dylib", home);
#else
    int len = snprintf(runtime, sizeof(runtime), "%s/lib/libjava.so", home);
#endif
    return len > 0 && len < (int)sizeof(runtime) && access(runtime, F_OK) == 0;
}

static int runtime_home_from_java_path(char *path) {
#ifdef _WIN32
    char *component = strrchr(path, '\\');
    if (!component) return 0;
    *component = '\0';
    component = strrchr(path, '\\');
#else
    char *component = strrchr(path, '/');
    if (!component) return 0;
    *component = '\0';
    component = strrchr(path, '/');
#endif
    if (!component) return 0;
    *component = '\0';
    return is_runtime_home(path);
}

static int parse_java_home(char *output, char *buf, size_t size) {
    const char *property = "java.home = ";
    char *value = strstr(output, property);
    if (!value) return 0;
    value += strlen(property);
    char *end = strpbrk(value, "\r\n");
    if (end) *end = '\0';
    while (*value && isspace((unsigned char)value[strlen(value) - 1]))
        value[strlen(value) - 1] = '\0';
    size_t length = strlen(value);
    if (length == 0 || length >= size) return 0;
    memcpy(buf, value, length + 1);
    return is_runtime_home(buf);
}

static int query_java_home(const char *java, char *buf, size_t size) {
    char output[65536];
    size_t used = 0;
#ifdef _WIN32
    SECURITY_ATTRIBUTES security = {
        .nLength = sizeof(SECURITY_ATTRIBUTES),
        .lpSecurityDescriptor = NULL,
        .bInheritHandle = TRUE
    };
    HANDLE read_pipe;
    HANDLE write_pipe;
    if (!CreatePipe(&read_pipe, &write_pipe, &security, 0)) return 0;
    if (!SetHandleInformation(read_pipe, HANDLE_FLAG_INHERIT, 0)) {
        CloseHandle(read_pipe);
        CloseHandle(write_pipe);
        return 0;
    }
    char command_line[8192];
    int length = snprintf(command_line, sizeof(command_line),
            "\"%s\" -XshowSettings:properties -version", java);
    if (length <= 0 || length >= (int)sizeof(command_line)) {
        CloseHandle(read_pipe);
        CloseHandle(write_pipe);
        return 0;
    }
    STARTUPINFOA startup = {
        .cb = sizeof(STARTUPINFOA),
        .dwFlags = STARTF_USESTDHANDLES,
        .hStdInput = GetStdHandle(STD_INPUT_HANDLE),
        .hStdOutput = write_pipe,
        .hStdError = write_pipe
    };
    PROCESS_INFORMATION process;
    BOOL created = CreateProcessA(java, command_line, NULL, NULL, TRUE,
            CREATE_NO_WINDOW, NULL, NULL, &startup, &process);
    CloseHandle(write_pipe);
    if (!created) {
        CloseHandle(read_pipe);
        return 0;
    }
    char chunk[4096];
    DWORD count;
    while (ReadFile(read_pipe, chunk, sizeof(chunk), &count, NULL) && count > 0) {
        size_t available = sizeof(output) - 1 - used;
        size_t retained = count < available ? count : available;
        if (retained > 0) {
            memcpy(output + used, chunk, retained);
            used += retained;
        }
    }
    CloseHandle(read_pipe);
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD result = 1;
    GetExitCodeProcess(process.hProcess, &result);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    if (result != 0) return 0;
#else
    int pipe_fds[2];
    if (pipe(pipe_fds) != 0) return 0;
    pid_t child = fork();
    if (child < 0) {
        close(pipe_fds[0]);
        close(pipe_fds[1]);
        return 0;
    }
    if (child == 0) {
        close(pipe_fds[0]);
        if (dup2(pipe_fds[1], STDOUT_FILENO) < 0
                || dup2(pipe_fds[1], STDERR_FILENO) < 0)
            _exit(1);
        close(pipe_fds[1]);
        execl(java, java, "-XshowSettings:properties", "-version", (char *)NULL);
        _exit(1);
    }
    close(pipe_fds[1]);
    char chunk[4096];
    ssize_t count;
    while ((count = read(pipe_fds[0], chunk, sizeof(chunk))) > 0) {
        size_t available = sizeof(output) - 1 - used;
        size_t retained = (size_t)count < available ? (size_t)count : available;
        if (retained > 0) {
            memcpy(output + used, chunk, retained);
            used += retained;
        }
    }
    close(pipe_fds[0]);
    int status;
    while (waitpid(child, &status, 0) < 0) {
        if (errno != EINTR) return 0;
    }
    if (!WIFEXITED(status) || WEXITSTATUS(status) != 0) return 0;
#endif
    output[used] = '\0';
    return parse_java_home(output, buf, size);
}

static int runtime_home_from_path(char *buf, size_t size) {
    char java[4096];
#ifdef _WIN32
    DWORD length = SearchPathA(NULL, "java.exe", NULL,
            (DWORD)sizeof(java), java, NULL);
    if (length == 0 || length >= sizeof(java)) return 0;
#else
    if (!resolve_from_path("java", java, sizeof(java))) return 0;
#endif
    memcpy(buf, java, strlen(java) + 1);
    if (runtime_home_from_java_path(buf)) return 1;
    return query_java_home(java, buf, size);
}

static int load_jli(const char *runtime_home, const char *tool) {
    char path[4096];
#ifdef _WIN32
    int length = snprintf(path, sizeof(path), "%s\\bin\\jli.dll", runtime_home);
    if (length <= 0 || length >= (int)sizeof(path)) return 0;
    HMODULE library = LoadLibraryA(path);
    if (!library) {
        fprintf(stderr, "%s: cannot load JDK launcher library: %s: error %lu\n",
                tool, path, (unsigned long)GetLastError());
        return 0;
    }
    FARPROC symbol = GetProcAddress(library, "JLI_Launch");
    if (!symbol) {
        fprintf(stderr, "%s: JDK launcher library does not export JLI_Launch: %s\n",
                tool, path);
        return 0;
    }
    memcpy(&jli_launch, &symbol, sizeof(jli_launch));
#else
#ifdef __APPLE__
    int length = snprintf(path, sizeof(path), "%s/lib/libjli.dylib", runtime_home);
#else
    int length = snprintf(path, sizeof(path), "%s/lib/libjli.so", runtime_home);
#endif
    if (length <= 0 || length >= (int)sizeof(path)) return 0;
    void *library = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!library) {
        fprintf(stderr, "%s: cannot load JDK launcher library: %s: %s\n",
                tool, path, dlerror());
        return 0;
    }
    void *symbol = dlsym(library, "JLI_Launch");
    if (!symbol) {
        fprintf(stderr, "%s: JDK launcher library does not export JLI_Launch: %s\n",
                tool, path);
        return 0;
    }
    memcpy(&jli_launch, &symbol, sizeof(jli_launch));
#endif
    return 1;
}

static unsigned long get_process_id(void) {
#ifdef _WIN32
    return (unsigned long)GetCurrentProcessId();
#else
    return (unsigned long)getpid();
#endif
}

static int is_directory(const char *path) {
#ifdef _WIN32
    DWORD attributes = GetFileAttributesA(path);
    return attributes != INVALID_FILE_ATTRIBUTES &&
            (attributes & FILE_ATTRIBUTE_DIRECTORY) != 0;
#else
    struct stat st;
    return stat(path, &st) == 0 && S_ISDIR(st.st_mode);
#endif
}

static int directory_has_regular_file_named(const char *path, const char *fragment) {
#ifdef _WIN32
    char pattern[4096];
    int length = snprintf(pattern, sizeof(pattern), "%s\\*", path);
    if (length <= 0 || length >= (int)sizeof(pattern)) return 0;
    WIN32_FIND_DATAA entry;
    HANDLE entries = FindFirstFileA(pattern, &entry);
    if (entries == INVALID_HANDLE_VALUE) return 0;
    int found = 0;
    do {
        if (!(entry.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) &&
                (!fragment || strstr(entry.cFileName, fragment))) {
            found = 1;
            break;
        }
    } while (FindNextFileA(entries, &entry));
    FindClose(entries);
    return found;
#else
    DIR *directory = opendir(path);
    if (!directory) return 0;
    int found = 0;
    struct dirent *entry;
    while ((entry = readdir(directory)) != NULL) {
        if (strcmp(entry->d_name, ".") == 0 || strcmp(entry->d_name, "..") == 0 ||
                (fragment && !strstr(entry->d_name, fragment)))
            continue;
        char candidate[4096];
        int length = snprintf(candidate, sizeof(candidate),
                "%s/%s", path, entry->d_name);
        struct stat st;
        if (length > 0 && length < (int)sizeof(candidate) &&
                stat(candidate, &st) == 0 && S_ISREG(st.st_mode)) {
            found = 1;
            break;
        }
    }
    closedir(directory);
    return found;
#endif
}

static int directory_has_regular_file(const char *path) {
    return directory_has_regular_file_named(path, NULL);
}

enum runtime_vm {
    RUNTIME_VM_UNKNOWN,
    RUNTIME_VM_HOTSPOT,
    RUNTIME_VM_OPENJ9
};

static enum runtime_vm detect_runtime_vm(const char *image_home) {
    char path[4096];
    int length;
    const char *openj9_directories[] = {
#ifdef _WIN32
        "bin\\default", "bin\\compressedrefs", "bin\\j9vm"
#else
        "lib/default", "lib/compressedrefs", "lib/j9vm"
#endif
    };
    for (size_t i = 0;
            i < sizeof(openj9_directories) / sizeof(openj9_directories[0]); i++) {
#ifdef _WIN32
        length = snprintf(path, sizeof(path),
                "%s\\%s", image_home, openj9_directories[i]);
#else
        length = snprintf(path, sizeof(path),
                "%s/%s", image_home, openj9_directories[i]);
#endif
        if (length > 0 && length < (int)sizeof(path) &&
                directory_has_regular_file_named(path, "j9vm"))
            return RUNTIME_VM_OPENJ9;
    }

#ifdef _WIN32
    length = snprintf(path, sizeof(path), "%s\\bin\\server\\jvm.dll", image_home);
#else
#ifdef __APPLE__
    length = snprintf(path, sizeof(path), "%s/lib/server/libjvm.dylib", image_home);
#else
    length = snprintf(path, sizeof(path), "%s/lib/server/libjvm.so", image_home);
#endif
#endif
    return length > 0 && length < (int)sizeof(path) && is_regular_file(path)
            ? RUNTIME_VM_HOTSPOT
            : RUNTIME_VM_UNKNOWN;
}

static int ensure_directory(const char *path) {
    char copy[4096];
    size_t length = strlen(path);
    if (length == 0 || length >= sizeof(copy)) return 0;
    memcpy(copy, path, length + 1);

    for (char *p = copy + 1; *p; p++) {
        if (*p != '/' && *p != '\\') continue;
#ifdef _WIN32
        if (p == copy + 2 && copy[1] == ':') continue;
#endif
        char separator = *p;
        *p = '\0';
        if (mkdir(copy, 0700) != 0 && errno != EEXIST) return 0;
        *p = separator;
    }
    return mkdir(copy, 0700) == 0 || errno == EEXIST;
}

enum aot_phase {
    AOT_PHASE_NONE,
    AOT_PHASE_RECORD,
    AOT_PHASE_CREATE,
    AOT_PHASE_OPENJ9,
    AOT_PHASE_INVALID
};

static enum aot_phase get_aot_phase(void) {
    const char *value = getenv("_JMOD_LAUNCHER_AOT_PHASE");
    if (!value) return AOT_PHASE_NONE;
    if (strcmp(value, "record") == 0) return AOT_PHASE_RECORD;
    if (strcmp(value, "create") == 0) return AOT_PHASE_CREATE;
    if (strcmp(value, "openj9") == 0) return AOT_PHASE_OPENJ9;
    return AOT_PHASE_INVALID;
}

static void publish_file(const char *temporary, const char *destination,
        const char *description, const char *tool) {
    if (access(temporary, F_OK) != 0) return;
#ifdef _WIN32
    if (MoveFileExA(temporary, destination,
            MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH)) return;
    if (access(destination, F_OK) == 0) {
        unlink(temporary);
        return;
    }
#else
    if (rename(temporary, destination) == 0) return;
#endif
    fprintf(stderr, "%s: cannot publish %s: %s\n",
            tool, description, destination);
    unlink(temporary);
}

static void append_file(const char *source, const char *destination) {
    FILE *input = fopen(source, "rb");
    if (!input) return;
    FILE *output = fopen(destination, "ab");
    if (!output) {
        fclose(input);
        return;
    }
    unsigned char buffer[8192];
    size_t count;
    while ((count = fread(buffer, 1, sizeof(buffer), input)) > 0)
        fwrite(buffer, 1, count, output);
    fclose(output);
    fclose(input);
    unlink(source);
}

static int launcher_main(int argc, char **argv);

static int launcher_identity_hash(
        unsigned long *hash,
        const char *tool,
        const char *release_path,
        const char *modules_path,
        int has_runtime_options,
        const char *runtime_options_path,
        int managed,
        const char *application_modules,
        const char *application_hash) {
    *hash = 5381;
    if (!hash_file(hash, release_path)) return 0;
    if (!hash_file_size(hash, modules_path)) return 0;
    *hash = djb2(*hash, tool, strlen(tool));
    if (has_runtime_options && !hash_file(hash, runtime_options_path)) return 0;
    if (managed) *hash = djb2(
            *hash, application_modules, strlen(application_modules));
    if (managed && !hash_file(hash, application_hash)) return 0;
    return 1;
}

/* The VM completes AOT output during process shutdown, so a parent process must
 * wait for the record and create children before it can publish the cache. */
static int run_aot_process(
        int argc, char **argv, const char *training_id, const char *phase) {
#ifdef _WIN32
    (void)argc;
    char executable[4096];
    if (!get_executable_path(argv[0], executable, sizeof(executable))) return 1;
    if (!SetEnvironmentVariableA("_JMOD_LAUNCHER_AOT_TRAINING", training_id)) return 1;
    if (!SetEnvironmentVariableA("_JMOD_LAUNCHER_AOT_PHASE", phase)) return 1;

    char *command_line = _strdup(GetCommandLineA());
    STARTUPINFOA startup = { .cb = sizeof(startup) };
    PROCESS_INFORMATION process;
    BOOL created = CreateProcessA(executable, command_line, NULL, NULL, TRUE, 0,
            NULL, NULL, &startup, &process);
    free(command_line);
    SetEnvironmentVariableA("_JMOD_LAUNCHER_AOT_TRAINING", NULL);
    SetEnvironmentVariableA("_JMOD_LAUNCHER_AOT_PHASE", NULL);
    if (!created) return 1;

    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD result = 1;
    GetExitCodeProcess(process.hProcess, &result);
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return (int)result;
#else
    pid_t child = fork();
    if (child < 0) return 1;
    if (child == 0) {
        if (setenv("_JMOD_LAUNCHER_AOT_TRAINING", training_id, 1) != 0) exit(1);
        if (setenv("_JMOD_LAUNCHER_AOT_PHASE", phase, 1) != 0) exit(1);
        exit(launcher_main(argc, argv));
    }

    int status;
    while (waitpid(child, &status, 0) < 0) {
        if (errno != EINTR) return 1;
    }
    if (WIFEXITED(status)) return WEXITSTATUS(status);
    if (WIFSIGNALED(status)) return 128 + WTERMSIG(status);
    return 1;
#endif
}

static int launcher_main(int argc, char **argv) {
    const char *tool = tool_name(argv[0]);
    if (!tool) {
        fprintf(stderr, "launcher: cannot determine tool name\n");
        return 1;
    }
    static char launcher_home[4096];
    if (!get_launcher_home(argv[0], launcher_home, sizeof(launcher_home))) {
        fprintf(stderr, "%s: cannot determine launcher home\n", tool);
        return 1;
    }
    static char runtime_home[4096];
    if (is_runtime_home(launcher_home)) {
        memcpy(runtime_home, launcher_home, strlen(launcher_home) + 1);
    } else {
        const char *java_home = getenv("JAVA_HOME");
        if (java_home && *java_home) {
            if (strlen(java_home) >= sizeof(runtime_home)) {
                fprintf(stderr, "%s: JAVA_HOME is too long\n", tool);
                return 1;
            }
            memcpy(runtime_home, java_home, strlen(java_home) + 1);
            if (!is_runtime_home(runtime_home)) {
                fprintf(stderr, "%s: JAVA_HOME is not a Java runtime image: %s\n",
                        tool, runtime_home);
                return 1;
            }
        } else if (!runtime_home_from_path(runtime_home, sizeof(runtime_home))) {
            fprintf(stderr,
                    "%s: JAVA_HOME is not set and java on PATH does not identify a JDK\n",
                    tool);
            return 1;
        }
    }
    if (!load_jli(runtime_home, tool)) return 1;

    enum aot_mode mode = AOT_OFF;
    int explicit_aot_mode = 0;
    if (!apply_launcher_options(
            tool, &argc, &argv, &mode, &explicit_aot_mode)) return 1;

    /* The VM validates lib/modules by size when loading an AOT cache. */
    static char release_path[4096];
    snprintf(release_path, sizeof(release_path), "%s/release", runtime_home);
    static char modules_path[4096];
    snprintf(modules_path, sizeof(modules_path), "%s/lib/modules", runtime_home);
    static char application_modules[4096];
    snprintf(application_modules, sizeof(application_modules),
            "%s/app/modules", launcher_home);
    static char application_hash[4096];
    snprintf(application_hash, sizeof(application_hash),
            "%s/app/modules.hash", launcher_home);
    int managed = is_directory(application_modules);
    int external_runtime = strcmp(launcher_home, runtime_home) != 0;
    if (external_runtime && (!managed || !is_regular_file(application_hash))) {
        fprintf(stderr, "%s: installed application is incomplete: %s\n",
                tool, launcher_home);
        return 1;
    }
    static char runtime_options_path[4096];
    int options_length = snprintf(runtime_options_path, sizeof(runtime_options_path),
            "%s/conf/com.netflix.tools.launcher/%s.args", launcher_home, tool);
    if (options_length < 0 || options_length >= (int)sizeof(runtime_options_path)) {
        fprintf(stderr, "%s: launcher runtime options path is too long\n", tool);
        return 1;
    }
    int has_runtime_options = is_regular_file(runtime_options_path);
    if (external_runtime && !has_runtime_options) {
        fprintf(stderr, "%s: installed application has no launcher arguments: %s\n",
                tool, runtime_options_path);
        return 1;
    }
    static char runtime_options[MAX_RUNTIME_OPTIONS][MAX_RUNTIME_OPTION_LENGTH];
    int runtime_option_count = 0;
    if (has_runtime_options) {
        runtime_option_count = load_runtime_options(
                tool, runtime_options_path, runtime_options,
                &mode, explicit_aot_mode);
        if (runtime_option_count < 0) return 1;
    }

    unsigned long hash;
    if (!launcher_identity_hash(
            &hash, tool, release_path, modules_path,
            has_runtime_options, runtime_options_path,
            managed, application_modules, application_hash)) {
        fprintf(stderr, "%s: cannot inspect launcher runtime: %s\n", tool, runtime_home);
        return 1;
    }

    enum runtime_vm runtime_vm = detect_runtime_vm(runtime_home);
    int hotspot_aot_supported = runtime_vm == RUNTIME_VM_HOTSPOT;
    int openj9_aot_supported = runtime_vm == RUNTIME_VM_OPENJ9;
    if (mode == AOT_CREATE && !hotspot_aot_supported && !openj9_aot_supported) {
        fprintf(stderr, "%s: runtime image does not support AOT\n", tool);
        return 1;
    }

    /* Platform cache directory:
     *   Unix:   $XDG_CACHE_HOME or ~/.cache
     *   Windows: %LOCALAPPDATA%
     */
    const char *cache_base;
#ifdef _WIN32
    cache_base = getenv("LOCALAPPDATA");
    if (cache_base && !*cache_base) cache_base = NULL;
#else
    cache_base = getenv("XDG_CACHE_HOME");
    if (!cache_base || cache_base[0] != '/') {
        cache_base = NULL;
        const char *home = getenv("HOME");
        if (home && home[0] == '/') {
            static char fallback[4096];
            snprintf(fallback, sizeof(fallback), "%s/.cache", home);
            cache_base = fallback;
        }
    }
#endif
    if (!cache_base && mode == AOT_CREATE && hotspot_aot_supported) {
        fprintf(stderr, "%s: cannot create AOT cache without a user cache directory\n", tool);
        return 1;
    }

    static char aot_dir[4096];
    if (cache_base)
        snprintf(aot_dir, sizeof(aot_dir), "%s/com.netflix.tools.launcher", cache_base);

    static char cache[4096];
    static char cache_dir[4096];
    static char attempted[4096];
    if (cache_base) {
        snprintf(cache_dir, sizeof(cache_dir), "%s/%016lx", aot_dir, hash);
        snprintf(cache, sizeof(cache), "%s/aot", cache_dir);
        snprintf(attempted, sizeof(attempted), "%s/attempted", cache_dir);
    }

    static char aot_flag[4200];
    static char shared_classes_flag[4200];
    static char configuration_flag[4200];
    static char log_flag[4200];
    static char xlog_flag[4200];
    static char temporary_cache[4096];
    static char temporary_configuration[4096];
    static char log[4096];
    static char temporary_log[4096];
    static char temporary_error[4096];
    static char temporary_xlog[4096];
    int warming = 0;
    int hotspot_warming = 0;
    int openj9_warming = 0;
    int recording = 0;
    const char *phase_flag = NULL;
    const char *training_id = getenv("_JMOD_LAUNCHER_AOT_TRAINING");
    enum aot_phase phase = get_aot_phase();

    int use_openj9_aot = openj9_aot_supported
            && (mode != AOT_OFF || training_id != NULL);
    if (use_openj9_aot && external_runtime && !cache_base) {
        if (mode == AOT_CREATE) {
            fprintf(stderr, "%s: cannot create shared class cache without a user cache directory\n",
                    tool);
            return 1;
        }
        use_openj9_aot = 0;
    }
    if (use_openj9_aot) {
        static char shared_classes_home[4096];
        if (external_runtime) {
            snprintf(shared_classes_home, sizeof(shared_classes_home), "%s", cache_dir);
        } else {
            snprintf(shared_classes_home, sizeof(shared_classes_home),
                    "%s/lib/ja", runtime_home);
        }
        static char shared_classes[4096];
        static char shared_classes_attempted[4096];
        static char shared_classes_output[4096];
        snprintf(shared_classes, sizeof(shared_classes),
                "%s/sharedclasses", shared_classes_home);
        snprintf(shared_classes_attempted, sizeof(shared_classes_attempted),
                "%s/sharedclasses.attempted", shared_classes_home);
        snprintf(shared_classes_output, sizeof(shared_classes_output),
                "%s/%s.out", shared_classes_home, tool);
        if (training_id) {
            char *end;
            unsigned long id = strtoul(training_id, &end, 10);
            if (*training_id == '\0' || *end != '\0') {
                fprintf(stderr, "%s: invalid internal AOT training identifier\n", tool);
                return 1;
            }
            if (phase != AOT_PHASE_OPENJ9) {
                fprintf(stderr, "%s: invalid internal AOT phase\n", tool);
                return 1;
            }
            snprintf(shared_classes_flag, sizeof(shared_classes_flag),
                    "-J-Xshareclasses:name=ja,cacheDir=%s,nonfatal", shared_classes);
            snprintf(temporary_error, sizeof(temporary_error),
                    "%s/.%lu.out.tmp", shared_classes_home, id);
            warming = 1;
            openj9_warming = 1;
        } else if (mode != AOT_CREATE && directory_has_regular_file(shared_classes)) {
            unlink(shared_classes_attempted);
            snprintf(shared_classes_flag, sizeof(shared_classes_flag),
                    "-J-Xshareclasses:name=ja,cacheDir=%s,readonly,nonfatal",
                    shared_classes);
        } else if (mode != AOT_AUTO
                || access(shared_classes_attempted, F_OK) != 0) {
            if (!ensure_directory(shared_classes)) {
                if (mode == AOT_CREATE) {
                    fprintf(stderr, "%s: cannot create shared class cache directory: %s\n",
                            tool, shared_classes);
                    return 1;
                }
            } else if (mode != AOT_AUTO
                    || create_file_exclusively(shared_classes_attempted)) {
                if (mode == AOT_AUTO && directory_has_regular_file(shared_classes)) {
                    unlink(shared_classes_attempted);
                    snprintf(shared_classes_flag, sizeof(shared_classes_flag),
                            "-J-Xshareclasses:name=ja,cacheDir=%s,readonly,nonfatal",
                            shared_classes);
                } else {
                    unsigned long pid = get_process_id();
                    char training_id_buffer[32];
                    snprintf(training_id_buffer, sizeof(training_id_buffer), "%lu", pid);
                    snprintf(temporary_error, sizeof(temporary_error),
                            "%s/.%lu.out.tmp", shared_classes_home, pid);
                    unlink(temporary_error);
                    fprintf(stderr, "%s: shared class cache warmup: %s\n",
                            tool, shared_classes);
                    int result = run_aot_process(
                            argc, argv, training_id_buffer, "openj9");
                    int created = directory_has_regular_file(shared_classes);
                    if (result != 0) {
                        fprintf(stderr,
                                "%s: shared class cache warmup failed with exit code %d\n",
                                tool, result);
                        publish_file(temporary_error, shared_classes_output,
                                "shared class cache warmup output", tool);
                    } else {
                        unlink(temporary_error);
                    }
                    if (!created) {
                        fprintf(stderr,
                                "%s: shared class cache warmup created no cache in %s\n",
                                tool, shared_classes);
                    }
                    if (mode == AOT_CREATE && (result != 0 || !created))
                        return result != 0 ? result : 1;
                    if (created) {
                        unlink(shared_classes_attempted);
                        snprintf(shared_classes_flag, sizeof(shared_classes_flag),
                                "-J-Xshareclasses:name=ja,cacheDir=%s,readonly,nonfatal",
                                shared_classes);
                    } else {
                        fprintf(stderr,
                                "%s: shared class cache creation failed; remove %s to retry\n",
                                tool, shared_classes_attempted);
                    }
                }
            }
        }
    }

    int use_aot = cache_base && hotspot_aot_supported
            && (mode != AOT_OFF || training_id != NULL);
    if (use_aot && !training_id && access(cache, F_OK) != 0 &&
            !ensure_directory(cache_dir)) {
        if (mode == AOT_CREATE) {
            fprintf(stderr, "%s: cannot create AOT cache directory: %s\n",
                    tool, cache_dir);
            return 1;
        }
        use_aot = 0;
    }
    if (use_aot && training_id) {
        char *end;
        unsigned long id = strtoul(training_id, &end, 10);
        if (*training_id == '\0' || *end != '\0') {
            fprintf(stderr, "%s: invalid internal AOT training identifier\n", tool);
            return 1;
        }
        if (phase != AOT_PHASE_RECORD && phase != AOT_PHASE_CREATE) {
            fprintf(stderr, "%s: invalid internal AOT phase\n", tool);
            return 1;
        }
        snprintf(temporary_cache, sizeof(temporary_cache),
                "%s/.%lu.aot.tmp", cache_dir, id);
        snprintf(temporary_configuration, sizeof(temporary_configuration),
                "%s/.%lu.aot.tmp.config", cache_dir, id);
        snprintf(temporary_log, sizeof(temporary_log),
                "%s/.%lu.log.tmp", cache_dir, id);
        snprintf(temporary_error, sizeof(temporary_error),
                "%s/.%lu.out.tmp", cache_dir, id);
        snprintf(temporary_xlog, sizeof(temporary_xlog),
                "%s/.%lu.xlog.tmp", cache_dir, id);
        snprintf(configuration_flag, sizeof(configuration_flag),
                "-J-XX:AOTConfiguration=%s", temporary_configuration);
        if (phase == AOT_PHASE_RECORD) {
            phase_flag = "-J-XX:AOTMode=record";
            recording = 1;
        } else {
            snprintf(aot_flag, sizeof(aot_flag),
                    "-J-XX:AOTCacheOutput=%s", temporary_cache);
            phase_flag = "-J-XX:AOTMode=create";
        }
        snprintf(log_flag, sizeof(log_flag), "-J-XX:LogFile=%s", temporary_log);
        snprintf(xlog_flag, sizeof(xlog_flag),
                "-J-Xlog:aot=info:file=%s::filecount=0", temporary_xlog);
        warming = 1;
        hotspot_warming = 1;
    } else if (use_aot && mode == AOT_AUTO && access(cache, F_OK) == 0) {
        snprintf(aot_flag, sizeof(aot_flag), "-J-XX:AOTCache=%s", cache);
    } else if (use_aot && mode == AOT_AUTO &&
            !create_file_exclusively(attempted)) {
        use_aot = 0;
    } else if (use_aot && mode == AOT_AUTO && access(cache, F_OK) == 0) {
        unlink(attempted);
        snprintf(aot_flag, sizeof(aot_flag), "-J-XX:AOTCache=%s", cache);
    } else if (use_aot) {
        if (mode == AOT_CREATE && !create_file_exclusively(attempted) &&
                access(attempted, F_OK) != 0) {
            fprintf(stderr, "%s: cannot mark AOT cache creation attempt: %s\n",
                    tool, attempted);
            return 1;
        }
        unsigned long pid = get_process_id();
        char training_id_buffer[32];
        snprintf(training_id_buffer, sizeof(training_id_buffer), "%lu", pid);
        snprintf(temporary_cache, sizeof(temporary_cache),
                "%s/.%lu.aot.tmp", cache_dir, pid);
        snprintf(temporary_configuration, sizeof(temporary_configuration),
                "%s/.%lu.aot.tmp.config", cache_dir, pid);
        snprintf(log, sizeof(log), "%s/log", cache_dir);
        static char output[4096];
        snprintf(output, sizeof(output), "%s/out", cache_dir);
        snprintf(temporary_log, sizeof(temporary_log),
                "%s/.%lu.log.tmp", cache_dir, pid);
        snprintf(temporary_error, sizeof(temporary_error),
                "%s/.%lu.out.tmp", cache_dir, pid);
        snprintf(temporary_xlog, sizeof(temporary_xlog),
                "%s/.%lu.xlog.tmp", cache_dir, pid);
        unlink(temporary_cache);
        unlink(temporary_configuration);
        unlink(temporary_log);
        unlink(temporary_error);
        unlink(temporary_xlog);
        fprintf(stderr,
                "%s: AOT training run, cache will be created at %s (log: %s, out: %s)\n",
                tool, cache, log, output);
        int result = run_aot_process(argc, argv, training_id_buffer, "record");
        int assembly_result = 0;
        unsigned long current_hash;
        int image_changed = !launcher_identity_hash(
                &current_hash, tool, release_path, modules_path,
                has_runtime_options, runtime_options_path,
                managed, application_modules, application_hash)
                || current_hash != hash;
        if (result != 0) {
            fprintf(stderr, "%s: AOT warmup failed with exit code %d\n", tool, result);
            unlink(temporary_configuration);
        } else if (image_changed) {
            fprintf(stderr,
                    "%s: image changed during AOT training; cache not created\n",
                    tool);
            unlink(temporary_configuration);
        } else if (access(temporary_configuration, F_OK) == 0) {
            assembly_result = run_aot_process(
                    argc, argv, training_id_buffer, "create");
            if (assembly_result == 0 && !is_regular_file(temporary_cache)) {
                fprintf(stderr, "%s: AOT assembly did not create cache: %s\n",
                        tool, temporary_cache);
                assembly_result = 1;
            }
            if (assembly_result == 0 && unlink(temporary_configuration) != 0) {
                fprintf(stderr, "%s: cannot remove AOT configuration: %s\n",
                        tool, temporary_configuration);
            } else if (assembly_result != 0) {
                fprintf(stderr,
                        "%s: AOT assembly failed; configuration retained at %s\n",
                        tool, temporary_configuration);
            }
        } else {
            fprintf(stderr, "%s: AOT training did not create configuration: %s\n",
                    tool, temporary_configuration);
            result = 1;
        }
        append_file(temporary_xlog, temporary_log);
        int created = result == 0 && assembly_result == 0 && !image_changed
                && is_regular_file(temporary_cache);
        if (created) {
            publish_file(temporary_cache, cache, "AOT cache", tool);
        } else {
            unlink(temporary_cache);
        }
        publish_file(temporary_log, log, "AOT log", tool);
        publish_file(temporary_error, output, "AOT output", tool);
        if (created && is_regular_file(cache)) {
            unlink(attempted);
            snprintf(aot_flag, sizeof(aot_flag), "-J-XX:AOTCache=%s", cache);
        } else {
            fprintf(stderr, "%s: AOT cache creation failed; remove %s to retry\n",
                    tool, attempted);
            if (mode == AOT_CREATE)
                return assembly_result != 0 ? assembly_result : result != 0 ? result : 1;
        }
    }

    const char *base_args[] = {
        "-J-Xms8m",
        "-m",
        "com.netflix.tools.launcher/com.netflix.tools.launcher.ToolLauncher",
        tool
    };
    int base_argc = (int)(sizeof(base_args) / sizeof(base_args[0]));
    int max_extra = 15; /* managed image, AOT, VM logging, launcher properties */
    const char *jargs[base_argc + max_extra + MAX_RUNTIME_OPTIONS];
    int jargc = 0;

    for (int i = 0; i < runtime_option_count; i++)
        jargs[jargc++] = runtime_options[i];
    if (aot_flag[0]) jargs[jargc++] = aot_flag;
    if (shared_classes_flag[0]) jargs[jargc++] = shared_classes_flag;
    if (configuration_flag[0]) jargs[jargc++] = configuration_flag;
    if (phase_flag) jargs[jargc++] = phase_flag;
    if (hotspot_warming) {
        jargs[jargc++] = "-J-XX:+UnlockDiagnosticVMOptions";
        jargs[jargc++] = "-J-XX:+LogVMOutput";
        jargs[jargc++] = "-J-XX:-DisplayVMOutput";
        jargs[jargc++] = log_flag;
        jargs[jargc++] = "-J-Xlog:aot=off";
        jargs[jargc++] = xlog_flag;
    }
    if (recording || openj9_warming)
        jargs[jargc++] = "-J-Dcom.netflix.tools.launcher.aot.training=true";
    if (managed) {
        jargs[jargc++] = external_runtime ? "--upgrade-module-path" : "--module-path";
        jargs[jargc++] = application_modules;
    }
    for (int i = 0; i < base_argc; i++)
        jargs[jargc++] = base_args[i];

    if (warming) {
        if (!freopen(temporary_error, "a", stdout)) return 1;
        if (!freopen(temporary_error, "a", stderr)) return 1;
    }

    return jli_launch(argc, argv,
            jargc, jargs,
            0, NULL,
            "1.0", "0.0",
            tool, tool,
            JNI_TRUE, JNI_FALSE, JNI_FALSE, 0);
}

int main(int argc, char **argv) {
    return launcher_main(argc, argv);
}
