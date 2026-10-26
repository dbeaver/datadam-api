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

import com.dbeaver.datadam.sso.api.DDSsoConstants;
import com.google.gson.annotations.SerializedName;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.jkiss.utils.oauth.OAuthConstants;

/**
 * OAuth access token and an optional internal identity JWT. This is not an OIDC contract.
 * expires_in is the access token lifetime in seconds; identity JWTs carry their own expiry.
 */
public record DDSsoTokenResponse(
    @SerializedName("access_token") @NotNull String accessToken,
    @SerializedName("token_type") @NotNull String tokenType,
    @SerializedName(DDSsoConstants.RESPONSE_EXPIRES_IN) long expiresIn,
    @NotNull String scope,
    @SerializedName(OAuthConstants.RESULT_PROP_TOKEN_ID) @Nullable String idToken
) {
}
