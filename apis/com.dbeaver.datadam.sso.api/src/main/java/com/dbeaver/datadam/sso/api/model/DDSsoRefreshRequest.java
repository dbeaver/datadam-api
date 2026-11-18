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
package com.dbeaver.datadam.sso.api.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

/**
 * RFC 6749 section 6. No access token, redirect URI or PKCE verifier is required.
 * An optional persisted successor allows recovery of a lost rotation response by proving possession of both tokens.
 */
public record DDSsoRefreshRequest(
    @NotNull String refreshToken, @NotNull String clientId, @Nullable String resource, @Nullable String scope,
    @Nullable String nextRefreshToken
) {
    public DDSsoRefreshRequest(
        @NotNull String refreshToken, @NotNull String clientId, @Nullable String resource, @Nullable String scope
    ) {
        this(refreshToken, clientId, resource, scope, null);
    }

    @Override
    @NotNull
    public String toString() {
        return "DDSsoRefreshRequest[redacted]";
    }
}
