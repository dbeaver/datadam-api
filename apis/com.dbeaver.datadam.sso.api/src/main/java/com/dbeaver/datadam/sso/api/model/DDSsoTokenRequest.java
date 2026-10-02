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

import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.utils.oauth.OAuthConstants;

/**
 * Backend authorization code exchange. HTTP controllers must explicitly bind the snake_case form parameters.
 */
public record DDSsoTokenRequest(
    @NotNull String code,
    @SerializedName(OAuthConstants.PARAM_CODE_VERIFIER) @NotNull String codeVerifier,
    @SerializedName(OAuthConstants.AUTH_PROP_CLIENT_ID) @NotNull String clientId,
    @SerializedName(OAuthConstants.PARAM_REDIRECT_URI) @NotNull String redirectUri
) {
}
