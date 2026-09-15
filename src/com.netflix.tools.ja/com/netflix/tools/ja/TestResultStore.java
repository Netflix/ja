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

package com.netflix.tools.ja;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.netflix.tools.ja.ExecutionTrace.Event;
import com.netflix.tools.ja.TestResult.Status;

/** Persistent test outcomes and the code observed during their execution. */
public final class TestResultStore {
    record Trace(String codeHash, String observedClassHash, Set<Event> events) {
        Trace {
            events = Set.copyOf(events);
        }
    }

    private static final int TRACE_FORMAT = 2;

    private final Path root;

    public TestResultStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    static TestResultStore defaults() {
        return forPlatform(System.getProperty("os.name"), Path.of(System.getProperty("user.home")), System.getenv());
    }

    static TestResultStore forPlatform(String operatingSystem, Path userHome, Map<String, String> environment) {
        Path cache;
        if (operatingSystem.toLowerCase(Locale.ROOT).contains("win")) {
            var local = environment.get("LOCALAPPDATA");
            cache = local == null || local.isBlank()
                    ? userHome.resolve("AppData/Local")
                    : Path.of(local);
        } else {
            var xdg = environment.get("XDG_CACHE_HOME");
            cache = xdg == null || xdg.isBlank()
                    ? userHome.resolve(".cache")
                    : Path.of(xdg);
        }
        return new TestResultStore(cache.resolve("com.netflix.tools.ja/tests"));
    }

    Path root() {
        return root;
    }

    public boolean hasSuccessfulResult(TestExecution execution) {
        return !hasFailure(execution) && Files.isRegularFile(successPath(execution));
    }

    public boolean hasFailure(TestExecution execution) {
        return Files.isRegularFile(failurePath(execution.selector()));
    }

    public Optional<Set<Event>> trace(String selector) throws IOException {
        return storedTrace(selector).map(Trace::events);
    }

    Optional<Trace> storedTrace(String selector) throws IOException {
        var path = tracePath(selector);
        if (!Files.isRegularFile(path)) {
            return Optional.empty();
        }
        return Optional.of(readTrace(Files.readAllBytes(path)));
    }

    void updateTrace(TestExecution execution) throws IOException {
        write(tracePath(execution.selector()), trace(execution));
    }

    public void record(TestResult result) throws IOException {
        var execution = result.execution();
        updateTrace(execution);
        if (result.status() == Status.SUCCESS) {
            write(successPath(execution), (execution.identity() + "\n").getBytes(StandardCharsets.UTF_8));
            Files.deleteIfExists(failurePath(execution.selector()));
        } else {
            clearSuccessfulResults(execution.selector());
            write(failurePath(execution.selector()), (execution.identity() + "\n").getBytes(StandardCharsets.UTF_8));
        }
    }

    private void clearSuccessfulResults(String selector) throws IOException {
        var directory = root.resolve("success").resolve(sha256(selector));
        if (!Files.isDirectory(directory)) {
            return;
        }
        try (var entries = Files.list(directory)) {
            for (var entry : entries.toList()) {
                Files.deleteIfExists(entry);
            }
        }
    }

    private Path successPath(TestExecution execution) {
        return root.resolve("success")
                   .resolve(sha256(execution.selector()))
                   .resolve(execution.identity());
    }

    private Path failurePath(String selector) {
        return root.resolve("failure").resolve(sha256(selector));
    }

    private Path tracePath(String selector) {
        return root.resolve("trace").resolve(sha256(selector));
    }

    private static byte[] trace(TestExecution execution) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var output = new DataOutputStream(bytes)) {
            output.writeInt(TRACE_FORMAT);
            write(output, execution.codeHash());
            write(output, execution.observedClassHash());
            var ordered = execution.trace().stream()
                    .sorted(Comparator.comparing(Event::method)
                            .thenComparing(Event::receiverModule, Comparator.nullsFirst(Comparator.naturalOrder()))
                            .thenComparing(Event::receiverClass, Comparator.nullsFirst(Comparator.naturalOrder())))
                    .toList();
            output.writeInt(ordered.size());
            for (var event : ordered) {
                write(output, event.method());
                writeNullable(output, event.receiverModule());
                writeNullable(output, event.receiverClass());
            }
        }
        return bytes.toByteArray();
    }

    private static Trace readTrace(byte[] content) throws IOException {
        try (var input = new DataInputStream(new ByteArrayInputStream(content))) {
            int format = input.readInt();
            String codeHash;
            String observedClassHash;
            int count;
            if (format == 1) {
                codeHash = "";
                observedClassHash = "";
                count = input.readInt();
            } else if (format == TRACE_FORMAT) {
                codeHash = read(input);
                observedClassHash = read(input);
                count = input.readInt();
            } else {
                throw new IOException("Unsupported incremental test trace format: " + format);
            }
            if (count < 0) {
                throw new IOException("Invalid incremental test trace event count: " + count);
            }
            var events = new LinkedHashSet<Event>();
            for (int i = 0; i < count; i++) {
                events.add(new Event(read(input), readNullable(input), readNullable(input)));
            }
            if (input.read() != -1) {
                throw new IOException("Trailing content in incremental test trace");
            }
            return new Trace(codeHash, observedClassHash, events);
        } catch (EOFException failure) {
            throw new IOException("Truncated incremental test trace", failure);
        }
    }

    private static void write(DataOutputStream output, String value) throws IOException {
        var content = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(content.length);
        output.write(content);
    }

    private static void writeNullable(DataOutputStream output, String value) throws IOException {
        output.writeBoolean(value != null);
        if (value != null) {
            write(output, value);
        }
    }

    private static String read(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0) {
            throw new IOException("Invalid incremental test trace string length: " + length);
        }
        var content = input.readNBytes(length);
        if (content.length != length) {
            throw new EOFException();
        }
        return new String(content, StandardCharsets.UTF_8);
    }

    private static String readNullable(DataInputStream input) throws IOException {
        return input.readBoolean() ? read(input) : null;
    }

    private static void write(Path target, byte[] content) throws IOException {
        Files.createDirectories(target.getParent());
        var temporary = Files.createTempFile(target.getParent(), ".result-", ".tmp");
        try {
            Files.write(temporary, content);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException _) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String sha256(String value) {
        return Sha256.hash(value);
    }
}
