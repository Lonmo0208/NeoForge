/*
 * Copyright (c) NeoForged and contributors
 * SPDX-License-Identifier: LGPL-2.1-only
 */

package net.neoforged.testframework.group;

import java.util.List;
import java.util.stream.Stream;
import org.jspecify.annotations.NullMarked;
import net.neoforged.testframework.Test;

@NullMarked
public interface Groupable {
    /**
     * Resolves all tests in this groupable element.
     * 
     * @return all tests
     */
    default List<Test> resolveAll() {
        return resolveAsStream().toList();
    }

    /**
     * Resolves all tests in this groupable element as a {@link Stream}.
     * 
     * @return all tests as a stream
     */
    Stream<Test> resolveAsStream();
}
