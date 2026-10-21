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
package com.dbeaver.datadam.external.api.rpc;

import org.jkiss.code.NotNull;

import java.util.Objects;

public final class DDRpcServices {
    public static final String ACCOUNT = "account";
    public static final String STORAGE = "storage";

    private DDRpcServices() {
    }

    @NotNull
    public static String topic(@NotNull String serviceId) {
        Objects.requireNonNull(serviceId);
        if (serviceId.isEmpty() || serviceId.length() > 128 || !serviceId.chars().allMatch(value ->
            value >= 'a' && value <= 'z' || value >= 'A' && value <= 'Z' || value >= '0' && value <= '9'
                || value == '.' || value == '_' || value == '-')) {
            throw new IllegalArgumentException("Expected a service ID of 1 to 128 ASCII letters, digits, dots, underscores or hyphens");
        }
        return "rpc." + serviceId;
    }
}
