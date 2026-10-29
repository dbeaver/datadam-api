/*
 * DBeaver - Universal Database Manager
 * Copyright (C) 2010-2026 DBeaver Corp
 *
 * All Rights Reserved.
 *
 * NOTICE:  All information contained herein is, and remains
 * the property of DBeaver Corp and its suppliers, if any.
 * The intellectual and technical concepts contained
 * herein are proprietary to DBeaver Corp and its suppliers
 * and may be covered by U.S. and Foreign Patents,
 * patents in process, and are protected by trade secret or copyright law.
 * Dissemination of this information or reproduction of this material
 * is strictly forbidden unless prior written permission is obtained
 * from DBeaver Corp.
 */
package com.dbeaver.datadam.sso.api;

import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DDSsoResourcesTest {
    private static final String BASE = "https://mcp.example/proxy/mcp";

    @Test
    void acceptsOnlyOneCanonicalUnreservedPathSegment() {
        Assertions.assertTrue(DDSsoResources.isDirectChild(BASE, BASE + "/DI_desktop-1"));
        Assertions.assertTrue(DDSsoResources.isDirectChild(BASE, BASE + "/desktop.1~test"));
        Assertions.assertFalse(DDSsoResources.isDirectChild(BASE, "https://mcp.example.evil/proxy/mcp/desktop"));
        Assertions.assertFalse(DDSsoResources.isDirectChild(BASE, "http://mcp.example/proxy/mcp/desktop"));
        Assertions.assertFalse(DDSsoResources.isDirectChild(BASE, BASE + "/" + "a".repeat(257)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "/", "/.", "/..", "/%2e%2e", "/%252e%252e", "/a/b", "/id/", "/id?query=1",
        "/id#fragment", "/%2fother", "/id;param=1", "/a b", "-other/id", "//id"})
    void rejectsTraversalEncodingAndAmbiguousResources(@NotNull String suffix) {
        Assertions.assertFalse(DDSsoResources.isDirectChild(BASE, BASE + suffix));
    }
}
