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
import org.jkiss.code.Nullable;

import java.util.Collection;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** OAuth scope-token syntax (RFC 6749 section 3.3) and stable wire representation. */
public final class DDSsoScopes {
    private static final Pattern TOKEN = Pattern.compile("[\\x21\\x23-\\x5B\\x5D-\\x7E]+");

    private DDSsoScopes() {
    }

    @NotNull
    public static Set<String> parse(@Nullable String scope) {
        if (scope == null || scope.isEmpty() || scope.length() > 4096) {
            throw new IllegalArgumentException("OAuth scopes are required");
        }
        Set<String> scopes = new TreeSet<>();
        for (String value : scope.split(" ", -1)) {
            if (!TOKEN.matcher(value).matches()) {
                throw new IllegalArgumentException("Invalid OAuth scope");
            }
            scopes.add(value);
        }
        return Set.copyOf(scopes);
    }

    @NotNull
    public static String format(@NotNull Collection<String> scopes) {
        String value = String.join(" ", new TreeSet<>(scopes));
        parse(value);
        return value;
    }
}
