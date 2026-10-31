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

package com.netflix.tools.json.test;

import module java.base;

import com.netflix.tools.json.Json;
import com.netflix.tools.json.JsonArray;
import com.netflix.tools.json.JsonBoolean;
import com.netflix.tools.json.JsonNumber;
import com.netflix.tools.json.JsonObject;
import com.netflix.tools.json.JsonParseException;
import com.netflix.tools.json.JsonString;
import com.netflix.tools.json.JsonValueException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonTest {
    @Test
    void navigatesDapArgumentsWithExplicitDefaults() {
        var request = Json
                .parse("""
                {"seq":1,"type":"request","command":"continue","arguments":{"threadId":7}}
                """);
        assertEquals(1, request.get("seq")
                               .asInt());
        assertEquals("continue", request.get("command")
                .asString());
        var arguments = request.get("arguments");
        assertEquals(7, arguments.get("threadId")
                .asInt());
        assertFalse(arguments.tryGet("singleThread")
                             .map(value -> value.asBoolean())
                             .orElse(false));
        assertThrows(JsonValueException.class, () -> arguments.get("missing"));
    }

    @Test
    void distinguishesAbsentAndNullMembers() {
        var object = Json.parse("{\"present\":null}");
        assertTrue(object.tryGet("missing")
                         .isEmpty());
        assertTrue(object.tryGet("present")
                         .isPresent());
        assertTrue(object.get("present")
                         .tryValue()
                         .isEmpty());
        assertThrows(JsonValueException.class,
                () -> object.get("present").asString());
    }

    @Test
    void rejectsDuplicateMembersAndExtendedSyntax() {
        for (var document : List.of("{\"seq\":1,\"seq\":2}", "{\"seq\":1,}", "[1,]", "{/* comment */ \"seq\":1}", "{\"seq\":1} {}")) {
            assertThrows(JsonParseException.class, () -> Json.parse(document), document);
        }
    }

    @Test
    void requiresExactNumericConversions() {
        assertEquals(9007199254740993L, Json.parse("9007199254740993")
                .asLong());
        assertEquals(Long.MAX_VALUE, Json.parse("9223372036854775807")
                .asLong());
        assertEquals(123, Json.parse("1.23e2")
                .asInt());
        assertEquals(123, Json.parse("123.0")
                .asInt());
        assertThrows(JsonValueException.class,
                () -> Json.parse("1.5").asInt());
        assertThrows(JsonValueException.class,
                () -> Json.parse("2147483648").asInt());
        assertThrows(JsonValueException.class,
                () -> Json.parse("9223372036854775808").asLong());
    }

    @Test
    void doesNotCoerceStringsOrBooleansToNumbers() {
        assertThrows(JsonValueException.class,
                () -> Json.parse("\"7\"").asInt());
        assertThrows(JsonValueException.class,
                () -> Json.parse("true").asInt());
        assertThrows(JsonValueException.class,
                () -> Json.parse("7").asBoolean());
    }

    @Test
    void generatesCompactResponseJson() {
        var response = JsonObject.of(
                Map.of(
                "seq", JsonNumber.of(2),
                "type", JsonString.of("response"),
                "request_seq", JsonNumber.of(1),
                "command", JsonString.of("threads"),
                "success", JsonBoolean.of(true),
                "body", JsonObject.of(
                        Map.of(
                                "threads",
                                JsonArray.of(List.of(JsonObject.of(Map.of(
                                "id", JsonNumber.of(7),
                                "name", JsonString.of("main")))))))));
        var wire = response.toString();
        assertFalse(wire.contains("\n"));
        var parsed = Json.parse(wire);
        assertEquals(1, parsed.get("request_seq")
                              .asInt());
        assertTrue(parsed.get("success")
                         .asBoolean());
        assertEquals(
                "main",
                parsed.get("body")
                      .get("threads")
                      .get(0)
                      .get("name")
                      .asString());
    }

    @Test
    void exposesUnmodifiableCollections() {
        var object = Json.parse("{\"items\":[1]}");
        assertThrows(UnsupportedOperationException.class,
                () -> object.asMap().put("extra", JsonNumber.of(2)));
        assertThrows(UnsupportedOperationException.class,
                () -> object.get("items")
                            .asList()
                            .add(JsonNumber.of(2)));
    }

    @Test
    void defensivelyCopiesCharacterInput() {
        var input = "{\"name\":\"main\"}".toCharArray();
        var parsed = Json.parse(input);
        Arrays.fill(input, 'x');
        assertEquals("main", parsed.get("name")
                .asString());
    }

    @Test
    void sharesLazyValuesBetweenVirtualThreads() throws Exception {
        var value = Json
                .parse("""
                {"number":9007199254740993,"text":"line\\nbreak"}
                """);
        Callable<String> read = () -> {
            assertEquals(9007199254740993L, value.get("number")
                    .asLong());
            assertEquals("line\nbreak", value.get("text")
                    .asString());
            return value.get("text").toString();
        };
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = IntStream.range(0, 64)
                    .mapToObj(_ -> read)
                    .toList();
            for (var result : executor.invokeAll(tasks)) {
                assertEquals("\"line\\nbreak\"", result.get());
            }
        }
    }
}
