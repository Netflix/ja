/*
 * Copyright 2026 Netflix, Inc.
 *
 * SPDX-License-Identifier: GPL-2.0-only WITH Classpath-exception-2.0
 *
 * This file is part of the OpenJDK JSON backport. Netflix extends the Classpath
 * exception to this compatibility implementation. See the accompanying
 * OpenJDK JSON license for the GPLv2 and Classpath exception text.
 */

package com.netflix.tools.json.internal;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * A JDK 25 compatibility helper for the JSON implementation's lazy caches.
 *
 * <p>Only successful initialization is cached. All access is synchronized so
 * shared immutable JSON values can safely initialize their caches concurrently.
 * This is not a general-purpose replacement for {@code java.lang.LazyConstant}.
 */
final class LazyValue<T> {
    private Supplier<? extends T> initializer;
    private T value;

    private LazyValue(Supplier<? extends T> initializer) {
        this.initializer = Objects.requireNonNull(initializer);
    }

    static <T> LazyValue<T> of(Supplier<? extends T> initializer) {
        return new LazyValue<>(initializer);
    }

    synchronized T get() {
        if (initializer != null) {
            value = initializer.get();
            initializer = null;
        }
        return value;
    }
}
