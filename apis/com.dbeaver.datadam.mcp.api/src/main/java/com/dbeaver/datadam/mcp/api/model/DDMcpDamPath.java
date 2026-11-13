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
package com.dbeaver.datadam.mcp.api.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

public final class DDMcpDamPath {
    private DDMcpDamPath() {
    }

    public static boolean isDamId(@Nullable String value) {
        return value != null && value.matches("[a-zA-Z0-9._~+/-]{1,256}") && !".".equals(value) && !"..".equals(value);
    }

    @NotNull
    public static String path(@NotNull String damId) {
        if (!isDamId(damId)) {
            throw new IllegalArgumentException("Invalid dam ID");
        }
        // deployment IDs can contain slashes; keep the endpoint in one URL segment
        String segment = damId.matches("[a-zA-Z0-9._-]+") ? damId
            : "~" + Base64.getUrlEncoder().withoutPadding().encodeToString(damId.getBytes(StandardCharsets.UTF_8));
        return "/mcp/" + segment;
    }

    @Nullable
    public static String damId(@NotNull String path) {
        if (!path.startsWith("/mcp/")) {
            return null;
        }
        String segment = path.substring(5);
        try {
            String damId = segment.startsWith("~")
                ? new String(Base64.getUrlDecoder().decode(segment.substring(1)), StandardCharsets.UTF_8) : segment;
            return isDamId(damId) && path(damId).equals(path) ? damId : null;
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }
}
