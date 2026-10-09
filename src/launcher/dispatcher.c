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

/* Stable command dispatcher for managed application images. */
#ifndef _WIN32
#define _GNU_SOURCE
#endif

#include <errno.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#ifdef _WIN32
#include <windows.h>
#else
#include <limits.h>
#include <unistd.h>
#endif

#ifndef PATH_MAX
#define PATH_MAX 4096
#endif

static int executable_path(const char *argv0, char *path, size_t size) {
#ifdef _WIN32
    (void)argv0;
    DWORD length = GetModuleFileNameA(NULL, path, (DWORD)size);
    return length > 0 && length < size;
#else
#ifdef __linux__
    ssize_t length = readlink("/proc/self/exe", path, size - 1);
    if (length >= 0) {
        path[length] = '\0';
        return 1;
    }
#endif
    if (!argv0 || !*argv0) return 0;
    if (strchr(argv0, '/')) return realpath(argv0, path) != NULL;

    const char *environment = getenv("PATH");
    if (!environment || !*environment) environment = ".";
    const char *component = environment;
    for (;;) {
        const char *end = strchr(component, ':');
        size_t length = end ? (size_t)(end - component) : strlen(component);
        int written = length == 0
                ? snprintf(path, size, "./%s", argv0)
                : snprintf(path, size, "%.*s/%s", (int)length, component, argv0);
        if (written > 0 && written < (int)size && access(path, X_OK) == 0) {
            char resolved[PATH_MAX];
            if (realpath(path, resolved)) {
                size_t resolved_length = strlen(resolved);
                if (resolved_length < size) {
                    memcpy(path, resolved, resolved_length + 1);
                    return 1;
                }
            }
        }
        if (!end) return 0;
        component = end + 1;
    }
#endif
}

static char *last_separator(char *path) {
    char *separator = strrchr(path, '/');
#ifdef _WIN32
    char *backslash = strrchr(path, '\\');
    if (!separator || (backslash && backslash > separator)) separator = backslash;
#endif
    return separator;
}

static int current_path(const char *argv0, char *path, size_t size) {
    if (!executable_path(argv0, path, size)) return 0;
    char *name = last_separator(path);
    name = name ? name + 1 : path;
#ifdef _WIN32
    size_t length = strlen(name);
    if (length > 4 && _stricmp(name + length - 4, ".exe") == 0)
        name[length - 4] = '\0';
#endif
    size_t used = strlen(path);
    return used + strlen(".current") < size
            && (strcpy(path + used, ".current"), 1);
}

static int target_path(const char *argv0, char *target, size_t size) {
    char current[PATH_MAX];
    if (!current_path(argv0, current, sizeof(current))) {
        fprintf(stderr, "launcher: cannot locate command executable\n");
        return 0;
    }
    FILE *file = fopen(current, "r");
    if (!file) {
        fprintf(stderr, "launcher: cannot read %s: %s\n", current, strerror(errno));
        return 0;
    }
    if (!fgets(target, (int)size, file)) {
        fprintf(stderr, "launcher: empty target in %s\n", current);
        fclose(file);
        return 0;
    }
    int extra = fgetc(file);
    fclose(file);
    size_t length = strlen(target);
    while (length > 0 && (target[length - 1] == '\n' || target[length - 1] == '\r'))
        target[--length] = '\0';
    if (length == 0 || (extra != EOF && extra != '\n' && extra != '\r')) {
        fprintf(stderr, "launcher: invalid target in %s\n", current);
        return 0;
    }
    if (target[0] == '/' ||
#ifdef _WIN32
            (length > 2 && target[1] == ':') || target[0] == '\\' ||
#endif
            0) return 1;

    char directory[PATH_MAX];
    if (!executable_path(argv0, directory, sizeof(directory))) return 0;
    char *separator = last_separator(directory);
    if (!separator) return 0;
    *separator = '\0';
#ifdef _WIN32
    int written = snprintf(current, sizeof(current), "%s\\%s", directory, target);
#else
    int written = snprintf(current, sizeof(current), "%s/%s", directory, target);
#endif
    if (written <= 0 || written >= (int)sizeof(current) || (size_t)written >= size) return 0;
    memcpy(target, current, (size_t)written + 1);
    return 1;
}

int main(int argc, char **argv) {
    (void)argc;
    char target[PATH_MAX];
    if (!target_path(argv[0], target, sizeof(target))) return 1;
#ifdef _WIN32
    char *command_line = _strdup(GetCommandLineA());
    if (!command_line) return 1;
    STARTUPINFOA startup = { .cb = sizeof(startup) };
    PROCESS_INFORMATION process;
    BOOL created = CreateProcessA(
            target, command_line, NULL, NULL, TRUE, 0, NULL, NULL, &startup, &process);
    free(command_line);
    if (!created) {
        fprintf(stderr, "launcher: cannot start %s: error %lu\n",
                target, (unsigned long)GetLastError());
        return 1;
    }
    CloseHandle(process.hThread);
    WaitForSingleObject(process.hProcess, INFINITE);
    DWORD result = 1;
    GetExitCodeProcess(process.hProcess, &result);
    CloseHandle(process.hProcess);
    return (int)result;
#else
    argv[0] = target;
    execv(target, argv);
    fprintf(stderr, "launcher: cannot start %s: %s\n", target, strerror(errno));
    return 1;
#endif
}
