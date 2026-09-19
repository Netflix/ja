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

package com.netflix.tools.launcher.test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeLauncherTest {
    private static final String STUB_JLI =
            """
            #include <stdio.h>
            #include <stdlib.h>
            #include <string.h>
            #include <unistd.h>

            typedef int jint;
            typedef unsigned char jboolean;

            static void append(const char *path, const char *value) {
                if (!path) return;
                FILE *file = fopen(path, "a");
                if (!file) exit(90);
                fputs(value, file);
                fclose(file);
            }

            int JLI_Launch(int argc, char **argv,
                    int jargc, const char **jargv,
                    int appclassc, const char **appclassv,
                    const char *fullversion,
                    const char *dotversion,
                    const char *pname,
                    const char *lname,
                    jboolean javaargs,
                    jboolean cpwildcard,
                    jboolean javaw,
                    jint ergo_class) {
                const char *configuration = NULL;
                const char *cache_output = NULL;
                const char *log = NULL;
                const char *phase = "run";
                int recording = 0;
                int assembling = 0;
                const char *training_phase = getenv("_JMOD_LAUNCHER_AOT_PHASE");
                int openj9 = training_phase && strcmp(training_phase, "openj9") == 0;
                if (openj9) phase = "openj9";
                char shared_cache_directory[4096] = {0};
                for (int i = 0; i < jargc; i++) {
                    if (strcmp(jargv[i], "-J-XX:AOTMode=record") == 0) {
                        recording = 1;
                        phase = "record";
                    }
                    if (strcmp(jargv[i], "-J-XX:AOTMode=create") == 0) {
                        assembling = 1;
                        phase = "create";
                    }
                    if (strncmp(jargv[i], "-J-XX:AOTConfiguration=", 23) == 0)
                        configuration = jargv[i] + 23;
                    if (strncmp(jargv[i], "-J-XX:AOTCacheOutput=", 21) == 0)
                        cache_output = jargv[i] + 21;
                    if (strncmp(jargv[i], "-J-XX:LogFile=", 14) == 0)
                        log = jargv[i] + 14;
                    const char *cache_dir = strstr(jargv[i], "cacheDir=");
                    if (cache_dir) {
                        cache_dir += strlen("cacheDir=");
                        size_t length = strcspn(cache_dir, ",");
                        if (length >= sizeof(shared_cache_directory)) return 93;
                        memcpy(shared_cache_directory, cache_dir, length);
                        shared_cache_directory[length] = '\0';
                    }
                }

                const char *phase_trace = getenv("TEST_PHASE_TRACE");
                if (phase_trace) {
                    char value[32];
                    snprintf(value, sizeof(value), "%s\\n", phase);
                    append(phase_trace, value);
                }
                const char *jargs_trace = getenv("TEST_JARGS");
                if (jargs_trace) {
                    FILE *file = fopen(jargs_trace, "a");
                    if (!file) return 91;
                    for (int i = 0; i < jargc; i++)
                        fprintf(file, "%s:%s\\n", phase, jargv[i]);
                    fclose(file);
                }
                const char *args_trace = getenv("TEST_ARGS");
                if (args_trace && !recording && !assembling) {
                    FILE *file = fopen(args_trace, "w");
                    for (int i = 1; i < argc; i++) fprintf(file, "%s\\n", argv[i]);
                    fclose(file);
                }
                const char *cwd_trace = getenv("TEST_CWD");
                if (cwd_trace && !recording && !assembling) {
                    char cwd[4096];
                    if (!getcwd(cwd, sizeof(cwd))) return 92;
                    FILE *file = fopen(cwd_trace, "w");
                    fprintf(file, "%s\\n", cwd);
                    fclose(file);
                }
                if (getenv("TEST_OUTPUT")) {
                    fprintf(stdout, "%s stdout\\n", phase);
                    fprintf(stderr, "%s stderr\\n", phase);
                }
                if (recording && configuration) {
                    FILE *file = fopen(configuration, "w");
                    fprintf(file, "configuration\\n");
                    fclose(file);
                }
                if (assembling && cache_output) {
                    FILE *file = fopen(cache_output, "w");
                    fprintf(file, "cache\\n");
                    fclose(file);
                }
                if (openj9 && shared_cache_directory[0]
                        && !getenv("TEST_SKIP_SHARED_CACHE")) {
                    char path[4096];
                    snprintf(path, sizeof(path), "%s/cache", shared_cache_directory);
                    FILE *file = fopen(path, "w");
                    if (!file) return 94;
                    fprintf(file, "cache\\n");
                    fclose(file);
                }
                if ((recording || assembling) && log) {
                    FILE *file = fopen(log, "a");
                    fprintf(file, "%s log\\n", phase);
                    fclose(file);
                }
                if (recording && getenv("TEST_MUTATE_IDENTITY")) {
                    FILE *file = fopen(getenv("TEST_MUTATE_IDENTITY"), "a");
                    fputs("changed", file);
                    fclose(file);
                }
                if (assembling) {
                    const char *result = getenv("TEST_ASSEMBLY_RESULT");
                    return result ? atoi(result) : 0;
                }
                if (recording) {
                    const char *result = getenv("TEST_WARMUP_RESULT");
                    return result ? atoi(result) : 0;
                }
                const char *result = getenv("TEST_RESULT");
                return result ? atoi(result) : 0;
            }
            """;

    @TempDir
    Path temporaryDirectory;

    private Path image;
    private Path cache;
    private Path launcher;
    private Path dispatcher;

    @BeforeAll
    static void requireNativeBinaries() {
        String message = "Native launcher binaries are missing; run src/launcher/build.sh first";
        assertTrue(Files.isRegularFile(nativeBinary("launcher")), message + ": " + nativeBinary("launcher"));
        assertTrue(Files.isRegularFile(nativeBinary("dispatcher")), message + ": " + nativeBinary("dispatcher"));
    }

    @Test
    void linuxLaunchersDoNotReserveStaticTls() throws Exception {
        Path binaries = projectRoot().resolve("src/com.netflix.tools.launcher/META-INF/com.netflix.tools.launcher");
        for (String architecture : List.of("aarch_64", "x86_64")) {
            Path binary = binaries.resolve("linux-" + architecture).resolve("launcher");
            assertEquals(0, staticTlsSize(binary), binary.toString());
        }
    }

    @BeforeEach
    void createImage() throws Exception {
        String executable = isWindows() ? ".exe" : "";
        launcher = nativeBinary("launcher");
        dispatcher = nativeBinary("dispatcher");

        image = temporaryDirectory.resolve("image");
        cache = temporaryDirectory.resolve("cache");
        Files.createDirectories(image.resolve("bin"));
        Files.createDirectories(image.resolve("lib"));
        Files.createDirectories(image.resolve("conf/com.netflix.tools.launcher"));
        Files.createDirectories(cache);
        Files.writeString(image.resolve("release"), "JAVA_VERSION=\"25.0.1\"\n");
        Files.writeString(image.resolve("lib/modules"), "modules");
        Path vm = hotSpotVm();
        Files.createDirectories(vm.getParent());
        Files.writeString(vm, "hotspot");
        Files.writeString(image.resolve("conf/com.netflix.tools.launcher/probe.args"), "-L-aot=auto\n");
        Path probe = image.resolve("bin/probe" + executable);
        Files.copy(launcher, probe, StandardCopyOption.REPLACE_EXISTING);
        probe.toFile().setExecutable(true);
        compileStubJli();
    }

    @Test
    void hotspotLayoutEnablesAotWithoutCustomReleaseMetadata() throws Exception {
        assertFalse(Files.readString(image.resolve("release"))
                .contains("VM="));
        Path phases = temporaryDirectory.resolve("phases");

        Result result = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=create"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));
    }

    @Test
    void autoCreatesAndUsesAotCache() throws Exception {
        Path phases = temporaryDirectory.resolve("phases");
        Path jargs = temporaryDirectory.resolve("jargs");

        Result result = run(Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_JARGS", jargs.toString()),
                List.of("argument"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));
        Path directory = cacheDirectory(result.output());
        assertTrue(Files.isRegularFile(directory.resolve("aot")));
        assertTrue(Files.isRegularFile(directory.resolve("log")));
        assertTrue(Files.isRegularFile(directory.resolve("out")));
        List<String> arguments = Files.readAllLines(jargs);
        assertTrue(arguments.contains("record:-J-Dcom.netflix.tools.launcher.aot.training=true"));
        assertFalse(arguments.stream()
                .anyMatch(value -> value.equals("run:-J-Dcom.netflix.tools.launcher.aot.training=true")));
        assertFalse(arguments.stream()
                .anyMatch(value -> value.contains("-L-aot")));

        Files.delete(phases);
        Result cached = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of());
        assertEquals(0, cached.exitCode(), cached.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));
    }

    @Test
    void commandWithoutAotLauncherArgumentDoesNotTrain() throws Exception {
        Files.writeString(image.resolve("conf/com.netflix.tools.launcher/probe.args"), "--enable-preview\n");
        Path phases = temporaryDirectory.resolve("phases");

        Result result = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of());

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));
        assertFalse(result.output()
                          .contains("AOT training run"));
    }

    @Test
    void explicitModesOverrideBundledAuto() throws Exception {
        Path phases = temporaryDirectory.resolve("phases");
        Result disabled = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=off"));
        assertEquals(0, disabled.exitCode(), disabled.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));

        Files.delete(phases);
        Result created = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=create"));
        assertEquals(0, created.exitCode(), created.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));

        Files.delete(phases);
        Result recreated = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=create"));
        assertEquals(0, recreated.exitCode(), recreated.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));

        Result invalid = run(Map.of(), List.of("-L-aot=invalid"));
        assertEquals(1, invalid.exitCode());
        assertTrue(invalid.output().contains("invalid -L-aot mode; expected auto, create, or off"),
                invalid.output());
    }

    @Test
    void explicitCreateDoesNotRequireBundledAuto() throws Exception {
        Files.writeString(image.resolve("conf/com.netflix.tools.launcher/probe.args"), "--enable-preview\n");
        Path phases = temporaryDirectory.resolve("phases");

        Result result = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=create"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));
    }

    @Test
    void autoFallsBackAfterWarmupFailureAndCreateIsStrict() throws Exception {
        Path phases = temporaryDirectory.resolve("phases");
        Result automatic = run(Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_WARMUP_RESULT", "7"), List.of());

        assertEquals(0, automatic.exitCode(), automatic.output());
        assertEquals(List.of("record", "run"), Files.readAllLines(phases));
        assertTrue(automatic.output().contains("AOT warmup failed with exit code 7"),
                automatic.output());
        assertFalse(Files.exists(cacheDirectory(automatic.output()).resolve("aot")));

        Files.delete(phases);
        Result create = run(Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_WARMUP_RESULT", "7"), List.of("-L-aot=create"));
        assertEquals(7, create.exitCode(), create.output());
        assertEquals(List.of("record"), Files.readAllLines(phases));
    }

    @Test
    void autoDoesNotRetryFailedWarmupUntilAttemptMarkerIsRemoved() throws Exception {
        Path phases = temporaryDirectory.resolve("phases");
        Result failed = run(Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_WARMUP_RESULT", "7"), List.of());

        assertEquals(0, failed.exitCode(), failed.output());
        assertEquals(List.of("record", "run"), Files.readAllLines(phases));
        Path attempt = cacheDirectory(failed.output()).resolve("attempted");
        assertTrue(Files.isRegularFile(attempt));
        assertTrue(failed.output().contains("remove " + attempt + " to retry"),
                failed.output());

        Files.delete(phases);
        Result skipped = run(Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_WARMUP_RESULT", "7"), List.of());
        assertEquals(0, skipped.exitCode(), skipped.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));
        assertFalse(skipped.output().contains("AOT training run"),
                skipped.output());

        Files.delete(phases);
        Files.delete(attempt);
        Result retried = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of());
        assertEquals(0, retried.exitCode(), retried.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));
        assertFalse(Files.exists(attempt));
    }

    @Test
    void autoFallsBackAfterAssemblyFailureWhileCreateIsStrict() throws Exception {
        Path phases = temporaryDirectory.resolve("phases");
        Result automatic = run(
                Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_ASSEMBLY_RESULT", "9"),
                List.of());

        assertEquals(0, automatic.exitCode(), automatic.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));
        assertTrue(automatic.output().contains("AOT assembly failed"),
                automatic.output());
        Path directory = cacheDirectory(automatic.output());
        assertFalse(Files.exists(directory.resolve("aot")));
        Path attempt = directory.resolve("attempted");
        assertTrue(Files.isRegularFile(attempt));
        assertTrue(automatic.output().contains("remove " + attempt + " to retry"),
                automatic.output());

        Files.delete(phases);
        Result skipped = run(
                Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_ASSEMBLY_RESULT", "9"),
                List.of());
        assertEquals(0, skipped.exitCode(), skipped.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));

        Files.delete(phases);
        Result create = run(
                Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_ASSEMBLY_RESULT", "9"),
                List.of("-L-aot=create"));
        assertEquals(9, create.exitCode(), create.output());
        assertEquals(List.of("record", "create"), Files.readAllLines(phases));
    }

    @Test
    void preservesTheRealRunResultAfterSuccessfulTraining() throws Exception {
        Result result = run(Map.of("TEST_RESULT", "4"), List.of());

        assertEquals(4, result.exitCode(), result.output());
        assertTrue(Files.isRegularFile(cacheDirectory(result.output()).resolve("aot")));
    }

    @Test
    void openj9CreatesAndUsesAnImageLocalSharedClassCache() throws Exception {
        configureOpenJ9();
        Path phases = temporaryDirectory.resolve("phases");
        Path jargs = temporaryDirectory.resolve("jargs");

        Result result = run(
                Map.of("XDG_CACHE_HOME", "", "HOME", "", "TEST_PHASE_TRACE",
                        phases.toString(), "TEST_JARGS", jargs.toString()),
                List.of());

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("openj9", "run"), Files.readAllLines(phases));
        Path sharedClasses = image.toRealPath().resolve("lib/ja/sharedclasses");
        assertTrue(Files.isRegularFile(sharedClasses.resolve("cache")));
        List<String> arguments = Files.readAllLines(jargs);
        assertTrue(arguments.stream()
                .anyMatch(value -> value.startsWith("openj9:-J-Xshareclasses:name=ja,cacheDir=" + sharedClasses)));
        assertTrue(arguments.stream()
                .anyMatch(value -> value.equals("run:-J-Xshareclasses:name=ja,cacheDir=" + sharedClasses + ",readonly,nonfatal")));

        Files.delete(phases);
        Result cached = run(
                Map.of("XDG_CACHE_HOME", "", "HOME", "", "TEST_PHASE_TRACE",
                        phases.toString()),
                List.of());
        assertEquals(0, cached.exitCode(), cached.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));

        Files.delete(phases);
        Result refreshed = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=create"));
        assertEquals(0, refreshed.exitCode(), refreshed.output());
        assertEquals(List.of("openj9", "run"), Files.readAllLines(phases));

        Path relocated = temporaryDirectory.resolve("relocated");
        Files.move(image, relocated);
        image = relocated;
        Files.delete(phases);
        Files.delete(jargs);
        Result moved = run(
                Map.of("XDG_CACHE_HOME", "", "HOME", "", "TEST_PHASE_TRACE",
                        phases.toString(), "TEST_JARGS", jargs.toString()),
                List.of());
        assertEquals(0, moved.exitCode(), moved.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));
        Path relocatedCache = image.toRealPath().resolve("lib/ja/sharedclasses");
        assertTrue(Files.readAllLines(jargs)
                .contains("run:-J-Xshareclasses:name=ja,cacheDir=" + relocatedCache + ",readonly,nonfatal"));
    }

    @Test
    void autoDoesNotRetryOpenJ9WarmupThatCreatesNoCache() throws Exception {
        configureOpenJ9();
        Path phases = temporaryDirectory.resolve("phases");

        Result failed = run(
                Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_SKIP_SHARED_CACHE", "1"),
                List.of());

        assertEquals(0, failed.exitCode(), failed.output());
        assertEquals(List.of("openj9", "run"), Files.readAllLines(phases));
        Path attempted = image.toRealPath().resolve("lib/ja/sharedclasses.attempted");
        assertTrue(Files.isRegularFile(attempted));
        assertTrue(failed.output().contains("remove " + attempted + " to retry"),
                failed.output());

        Files.delete(phases);
        Result skipped = run(
                Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_SKIP_SHARED_CACHE", "1"),
                List.of());
        assertEquals(0, skipped.exitCode(), skipped.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));

        Files.delete(phases);
        Result explicit = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of("-L-aot=create"));
        assertEquals(0, explicit.exitCode(), explicit.output());
        assertEquals(List.of("openj9", "run"), Files.readAllLines(phases));
        assertFalse(Files.exists(attempted));
    }

    @Test
    void unknownVmLayoutDisablesAot() throws Exception {
        Files.delete(hotSpotVm());
        Path phases = temporaryDirectory.resolve("phases");

        Result automatic = run(Map.of("TEST_PHASE_TRACE", phases.toString()), List.of());
        assertEquals(0, automatic.exitCode(), automatic.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));

        Result create = run(Map.of(), List.of("-L-aot=create"));
        assertEquals(1, create.exitCode());
        assertTrue(create.output().contains("runtime image does not support AOT"),
                create.output());
    }

    @Test
    void capturesWarmupOutputSeparatelyFromTheRealRun() throws Exception {
        Result result = run(Map.of("TEST_OUTPUT", "1"), List.of());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("run stdout"),
                result.output());
        assertTrue(result.output().contains("run stderr"),
                result.output());
        assertFalse(result.output().contains("record stdout"),
                result.output());
        String warmup = Files.readString(cacheDirectory(result.output()).resolve("out"));
        assertTrue(warmup.contains("record stdout"), warmup);
        assertTrue(warmup.contains("record stderr"), warmup);
        assertTrue(warmup.contains("create stdout"), warmup);
        assertTrue(warmup.contains("create stderr"), warmup);
    }

    @Test
    void runtimeAndLauncherArgumentsAreSeparated() throws Exception {
        Files.writeString(image.resolve("conf/com.netflix.tools.launcher/probe.args"), "--enable-preview\n-L-aot=auto\n");
        Path jargs = temporaryDirectory.resolve("jargs");
        Path args = temporaryDirectory.resolve("args");

        Result result = run(Map.of("TEST_JARGS", jargs.toString(), "TEST_ARGS", args.toString()),
                List.of("-L-aot=off", "argument"));

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(Files.readAllLines(jargs)
                .contains("run:--enable-preview"));
        assertFalse(Files.readString(jargs)
                .contains("-L-aot"));
        assertEquals(List.of("argument"), Files.readAllLines(args));
    }

    @Test
    void rejectsInvalidBundledArgumentsAndDuplicateModes() throws Exception {
        Path options = image.resolve("conf/com.netflix.tools.launcher/probe.args");
        Files.writeString(options, "-Xmx1g\n");
        Result runtime = run(Map.of(), List.of());
        assertEquals(1, runtime.exitCode());
        assertTrue(runtime.output().contains("invalid launcher runtime option"),
                runtime.output());

        Files.writeString(options, "-L-aot=auto\n-L-aot=off\n");
        Result duplicate = run(Map.of(), List.of());
        assertEquals(1, duplicate.exitCode());
        assertTrue(duplicate.output().contains("duplicate launcher AOT mode"),
                duplicate.output());
    }

    @Test
    void passesArgumentFilesThroughToTheTool() throws Exception {
        Path working = Files.createDirectories(temporaryDirectory.resolve("working directory"));
        Path nested = temporaryDirectory.resolve("nested.args");
        Files.writeString(nested, "nested");
        Path argumentFile = temporaryDirectory.resolve("launcher.args");
        Files.writeString(argumentFile,
                """
                # launcher options
                -L-aot=off
                -C
                "%s"
                --
                "one value"
                @@literal
                @%s
                """
                        .formatted(working, nested));
        Path args = temporaryDirectory.resolve("args");
        Path cwd = temporaryDirectory.resolve("cwd");
        Path jargs = temporaryDirectory.resolve("jargs");

        Result result = run(
                Map.of("TEST_ARGS", args.toString(), "TEST_CWD", cwd.toString(),
                        "TEST_JARGS", jargs.toString()),
                List.of("@" + argumentFile));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("@" + argumentFile), Files.readAllLines(args));
        assertEquals(Path.of("").toRealPath(),
                Path.of(Files.readString(cwd).strip()));
        assertFalse(Files.readAllLines(jargs).stream()
                .anyMatch(argument -> argument.contains("arguments.expanded")));
    }

    @Test
    void preservesArgumentFilesDuringAotTraining() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.args");
        Path argumentFile = temporaryDirectory.resolve("tool.args");
        Files.writeString(argumentFile, "@" + missing + "\n");
        Path phases = temporaryDirectory.resolve("phases");
        Path args = temporaryDirectory.resolve("args");

        Result result = run(Map.of("TEST_PHASE_TRACE", phases.toString(), "TEST_ARGS", args.toString()),
                List.of("@" + argumentFile));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("record", "create", "run"), Files.readAllLines(phases));
        assertEquals(List.of("@" + argumentFile), Files.readAllLines(args));
    }

    @Test
    void passesEscapedAtPrefixedArgumentsThrough() throws Exception {
        Path args = temporaryDirectory.resolve("args");

        Result result = run(Map.of("TEST_ARGS", args.toString()), List.of("@@literal"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("@@literal"), Files.readAllLines(args));
    }

    @Test
    void doesNotReadArgumentFilesForTheTool() throws Exception {
        Path missing = temporaryDirectory.resolve("missing.args");
        Path args = temporaryDirectory.resolve("args");

        Result result = run(Map.of("TEST_ARGS", args.toString()), List.of("@" + missing));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("@" + missing), Files.readAllLines(args));
    }

    @Test
    void launcherOptionParsingStopsAtTheToolArguments() throws Exception {
        Path args = temporaryDirectory.resolve("args");
        Result result = run(Map.of("TEST_ARGS", args.toString()), List.of("-L-aot=off", "--", "-L-aot=create", "-C", "literal"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(List.of("-L-aot=create", "-C", "literal"), Files.readAllLines(args));
    }

    @Test
    void autoRunsNormallyWithoutAUserCacheAndCreateFails() throws Exception {
        Path phases = temporaryDirectory.resolve("phases");
        Result automatic = run(
                Map.of("XDG_CACHE_HOME", "", "HOME", "", "TEST_PHASE_TRACE",
                        phases.toString()),
                List.of());

        assertEquals(0, automatic.exitCode(), automatic.output());
        assertEquals(List.of("run"), Files.readAllLines(phases));

        Result create = run(Map.of("XDG_CACHE_HOME", "", "HOME", ""), List.of("-L-aot=create"));
        assertEquals(1, create.exitCode());
        assertTrue(create.output().contains("cannot create AOT cache without a user cache directory"),
                create.output());
    }

    @Test
    void discardsTrainingWhenTheImageChanges() throws Exception {
        Path modules = image.resolve("lib/modules");
        Result result = run(Map.of("TEST_MUTATE_IDENTITY", modules.toString()), List.of());

        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("image changed during AOT training"),
                result.output());
        assertFalse(Files.exists(cacheDirectory(result.output()).resolve("aot")));
    }

    @Test
    void passesWorkingDirectoryArgumentsThroughToTheTool() throws Exception {
        Path working = Files.createDirectories(temporaryDirectory.resolve("working"));
        Path cwd = temporaryDirectory.resolve("cwd");
        Path args = temporaryDirectory.resolve("args");

        Result result = run(Map.of("TEST_CWD", cwd.toString(), "TEST_ARGS", args.toString()),
                List.of("-C", working.toString(), "argument"));

        assertEquals(0, result.exitCode(), result.output());
        assertEquals(Path.of("").toRealPath(),
                Path.of(Files.readString(cwd).strip()));
        assertEquals(List.of("-C", working.toString(), "argument"), Files.readAllLines(args));
    }

    @Test
    void imageIdentityChangesWithReleaseAndModules() throws Exception {
        Result first = run(Map.of(), List.of());
        Path firstDirectory = cacheDirectory(first.output());

        Files.writeString(image.resolve("lib/modules"), "larger modules image");
        Result modulesChanged = run(Map.of(), List.of());
        Path secondDirectory = cacheDirectory(modulesChanged.output());
        assertNotEquals(firstDirectory, secondDirectory);

        Files.writeString(image.resolve("release"), "JAVA_VERSION=\"25.0.2\"\n");
        Result releaseChanged = run(Map.of(), List.of());
        assertNotEquals(secondDirectory, cacheDirectory(releaseChanged.output()));
    }

    @Test
    void dispatcherFollowsUpdatedTarget() throws Exception {
        if (isWindows()) {
            return;
        }
        Path installed = temporaryDirectory.resolve("probe");
        Files.copy(dispatcher, installed, StandardCopyOption.REPLACE_EXISTING);
        installed.toFile().setExecutable(true);
        Path first = script("first", 7);
        Path second = script("second", 9);
        Files.writeString(installed.resolveSibling("probe.current"), first.toString());

        Result initial = runProcess(installed, Map.of(), List.of("argument"));
        assertEquals(7, initial.exitCode());
        assertEquals("first:argument", initial.output()
                .strip());

        Files.writeString(installed.resolveSibling("probe.current"), second.toString());
        Result updated = runProcess(installed, Map.of(), List.of("argument"));
        assertEquals(9, updated.exitCode());
        assertEquals("second:argument", updated.output()
                .strip());
    }

    private Result run(Map<String, String> environment, List<String> arguments) throws Exception {
        return runProcess(image.resolve("bin/probe" + (isWindows() ? ".exe" : "")), environment,
                arguments);
    }

    private Result runProcess(Path executable, Map<String, String> environment, List<String> arguments) throws Exception {
        var command = new ArrayList<String>();
        command.add(executable.toString());
        command.addAll(arguments);
        var process = new ProcessBuilder(command).redirectErrorStream(true);
        Map<String, String> processEnvironment = process.environment();
        processEnvironment.put("XDG_CACHE_HOME", cache.toString());
        processEnvironment.putAll(environment);
        Process child = process.start();
        String output = new String(child.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        return new Result(child.waitFor(), output);
    }

    private Path cacheDirectory(String output) {
        var matcher = Pattern.compile("cache will be created at (.+[/\\\\]aot) ").matcher(output);
        assertTrue(matcher.find(), output);
        return Path.of(matcher.group(1)).getParent();
    }

    private Path script(String name, int exitCode) throws IOException {
        Path script = temporaryDirectory.resolve(name);
        Files.writeString(script, "#!/bin/sh\nprintf '" + name + ":%s\\n' \"$1\"\nexit " + exitCode + "\n");
        script.toFile().setExecutable(true);
        return script;
    }

    private void compileStubJli() throws Exception {
        if (isWindows()) {
            throw new UnsupportedOperationException("Native launcher tests are not yet supported on Windows");
        }
        Path source = temporaryDirectory.resolve("stub_jli.c");
        Files.writeString(source, STUB_JLI);
        boolean mac = System.getProperty("os.name").equals("Mac OS X");
        Path library = image.resolve("lib").resolve(mac ? "libjli.dylib" : "libjli.so");
        var command = new ArrayList<String>();
        command.add("cc");
        if (mac) {
            command.add("-dynamiclib");
        } else {
            command.add("-shared");
            command.add("-fPIC");
        }
        command.add(source.toString());
        if (mac) {
            command.add("-Wl,-install_name,@rpath/libjli.dylib");
        } else {
            command.add("-Wl,-soname,libjli.so");
        }
        command.add("-o");
        command.add(library.toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream()
                .readAllBytes(),
                        StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        Files.createFile(image.resolve("lib")
                              .resolve(mac ? "libjava.dylib" : "libjava.so"));
    }

    private static Path projectRoot() {
        Path directory = Path.of("")
                .toAbsolutePath()
                .normalize();
        while (directory != null) {
            if (Files.isDirectory(directory.resolve("src/launcher"))) {
                return directory;
            }
            directory = directory.getParent();
        }
        throw new IllegalStateException("Cannot locate project root");
    }

    private static Path nativeBinary(String name) {
        return projectRoot().resolve("src/com.netflix.tools.launcher/META-INF/com.netflix.tools.launcher")
                            .resolve(classifier())
                            .resolve(name + (isWindows() ? ".exe" : ""));
    }

    private static long staticTlsSize(Path binary) throws IOException {
        ByteBuffer elf = ByteBuffer.wrap(Files.readAllBytes(binary)).order(ByteOrder.LITTLE_ENDIAN);
        if (elf.get(0) != 0x7f
                || elf.get(1) != 'E'
                || elf.get(2) != 'L'
                || elf.get(3) != 'F'
                || elf.get(4) != 2
                || elf.get(5) != 1) {
            throw new IOException("Expected a little-endian ELF64 executable: " + binary);
        }
        long programHeaders = elf.getLong(32);
        int entrySize = Short.toUnsignedInt(elf.getShort(54));
        int entries = Short.toUnsignedInt(elf.getShort(56));
        long total = 0;
        for (int index = 0; index < entries; index++) {
            int offset = Math.toIntExact(programHeaders + (long) index * entrySize);
            if (elf.getInt(offset) == 7) { // PT_TLS
                total = Math.addExact(total, elf.getLong(offset + 40));
            }
        }
        return total;
    }

    private static String classifier() {
        String architecture = System.getProperty("os.arch");
        architecture = switch (architecture) {
                    case "aarch64", "arm64" -> "aarch_64";
                    case "amd64", "x86_64" -> "x86_64";
                    default -> throw new IllegalStateException("Unsupported test architecture: " + architecture);
                };
        String operatingSystem = System.getProperty("os.name");
        String os = operatingSystem.equals("Mac OS X")
                ? "osx"
                : operatingSystem.startsWith("Windows") ? "windows" : "linux";
        return os + "-" + architecture;
    }

    private void configureOpenJ9() throws IOException {
        Path vm = openJ9Vm();
        Files.createDirectories(vm.getParent());
        Files.writeString(vm, "openj9");
    }

    private Path hotSpotVm() {
        if (isWindows()) {
            return image.resolve("bin/server/jvm.dll");
        }
        String library = System.getProperty("os.name").equals("Mac OS X") ? "libjvm.dylib" : "libjvm.so";
        return image.resolve("lib/server").resolve(library);
    }

    private Path openJ9Vm() {
        if (isWindows()) {
            return image.resolve("bin/default/j9vm29.dll");
        }
        String library = System.getProperty("os.name").equals("Mac OS X") ? "libj9vm29.dylib" : "libj9vm29.so";
        return image.resolve("lib/default").resolve(library);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").startsWith("Windows");
    }

    private record Result(int exitCode, String output) {}
}
