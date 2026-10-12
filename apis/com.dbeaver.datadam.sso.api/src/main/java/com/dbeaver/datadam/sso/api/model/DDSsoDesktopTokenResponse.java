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
import org.jkiss.utils.oauth.OAuthConstants;

/** Tokens issued solely to the public desktop client. */
public record DDSsoDesktopTokenResponse(
    @SerializedName("access_token") @NotNull String accessToken,
    @SerializedName("token_type") @NotNull String tokenType,
    @SerializedName(DDSsoConstants.RESPONSE_EXPIRES_IN) long expiresIn,
    @SerializedName(OAuthConstants.RESULT_PROP_TOKEN_ID) @NotNull String idToken
) {
}
