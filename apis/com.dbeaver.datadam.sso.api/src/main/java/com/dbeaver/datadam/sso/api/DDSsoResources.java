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

import java.util.regex.Pattern;

public final class DDSsoResources {
    private static final Pattern CHILD_ID = Pattern.compile("[A-Za-z0-9._~-]{1,256}");

    private DDSsoResources() {
    }

    public static boolean isDirectChild(@NotNull String parent, @NotNull String resource) {
        if (!resource.startsWith(parent + "/")) {
            return false;
        }
        String child = resource.substring(parent.length() + 1);
        return CHILD_ID.matcher(child).matches() && !".".equals(child) && !"..".equals(child);
    }
}
