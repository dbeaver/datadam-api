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
import com.nimbusds.jose.jwk.JWKParameterNames;
import org.jkiss.code.NotNull;

/** Public parameters of an Ed25519 (OKP) key. Private key material is never part of this model. */
public record DDSsoPublicJwk(
    @SerializedName(JWKParameterNames.KEY_TYPE) @NotNull String keyType,
    @SerializedName(JWKParameterNames.KEY_ID) @NotNull String keyId,
    @SerializedName(JWKParameterNames.PUBLIC_KEY_USE) @NotNull String keyUse,
    @SerializedName(JWKParameterNames.ALGORITHM) @NotNull String algorithm,
    @SerializedName(JWKParameterNames.OKP_SUBTYPE) @NotNull String curve,
    @SerializedName(JWKParameterNames.OKP_PUBLIC_KEY) @NotNull String publicKey
) {
}
