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

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Builds unambiguous SHA-256 identities from length-prefixed values. */
final class Sha256 {
    private final MessageDigest digest = newDigest();

    Sha256 add(int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
        return this;
    }

    Sha256 add(String value) {
        return add(value.getBytes(StandardCharsets.UTF_8));
    }

    Sha256 add(byte[] value) {
        add(value.length);
        digest.update(value);
        return this;
    }

    String hex() {
        return HexFormat.of().formatHex(digest.digest());
    }

    static String hash(String value) {
        return new Sha256().add(value).hex();
    }

    static byte[] hashBytes(byte[] value) {
        var digest = newDigest();
        return digest.digest(value);
    }

    static String hash(Path path) throws IOException {
        var digest = newDigest();
        try (var input = Files.newInputStream(path)) {
            var buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException _) {
            throw new AssertionError("SHA-256 is unavailable");
        }
    }
}
