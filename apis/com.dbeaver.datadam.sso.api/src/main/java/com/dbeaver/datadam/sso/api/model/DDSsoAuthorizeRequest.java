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
 * Parameters of an authorization request. Only the S256 PKCE challenge method is supported.
 * HTTP controllers must explicitly bind the corresponding snake_case query parameters.
 * Ordinary DataDam access uses scopes only. Resource identifies an explicitly configured target such as MCP.
 */
public record DDSsoAuthorizeRequest(
    @NotNull String clientId,
    @NotNull String redirectUri,
    @NotNull String state,
    @NotNull String codeChallenge,
    @NotNull String codeChallengeMethod,
    @Nullable String resource,
    @NotNull String scope
) {
}
