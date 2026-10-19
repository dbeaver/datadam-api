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
package com.dbeaver.datadam.external.api;

import org.jkiss.code.Nullable;

public final class DDExternalDamApi {
    public static final String GRAPHQL_PATH = "/internal/graphql";
    public static final String HEADER_SERVICE_TOKEN = "X-DD-Service-Token";
    public static final int MIN_SERVICE_SECRET_LENGTH = 32;
    public static final int MAX_SERVICE_SECRET_LENGTH = 512;

    private DDExternalDamApi() {
    }

    public static boolean isValidServiceSecret(@Nullable String secret) {
        return secret != null && secret.length() >= MIN_SERVICE_SECRET_LENGTH && secret.length() <= MAX_SERVICE_SECRET_LENGTH
            && secret.chars().allMatch(value -> value >= 33 && value <= 126);
    }
}
