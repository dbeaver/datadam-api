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
package com.dbeaver.datadam.dam.remote.api.model;

import org.jkiss.code.NotNull;

import java.util.Map;

public record DDRemoteDamSettings(@NotNull Map<String, String> account, @NotNull Map<String, String> dam) {
    public static final DDRemoteDamSettings EMPTY = new DDRemoteDamSettings(Map.of(), Map.of());

    public DDRemoteDamSettings {
        account = Map.copyOf(account);
        dam = Map.copyOf(dam);
    }

    public boolean isAccountEnabled(@NotNull String key, boolean defaultValue) {
        return Boolean.parseBoolean(account.getOrDefault(key, Boolean.toString(defaultValue)));
    }

    public boolean isDamEnabled(@NotNull String key, boolean defaultValue) {
        return Boolean.parseBoolean(dam.getOrDefault(key, Boolean.toString(defaultValue)));
    }

    public boolean isEnabled(@NotNull String key, boolean accountDefault, boolean damDefault) {
        return isAccountEnabled(key, accountDefault) && isDamEnabled(key, damDefault);
    }
}
