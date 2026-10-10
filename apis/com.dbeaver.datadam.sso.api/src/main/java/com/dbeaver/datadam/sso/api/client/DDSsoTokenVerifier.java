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
package com.dbeaver.datadam.sso.api.client;

import com.dbeaver.datadam.sso.api.DDSsoConstants;
import com.dbeaver.datadam.sso.api.exception.DDSsoClientException;
import com.dbeaver.datadam.sso.api.model.DDSsoAccessIdentity;
import com.dbeaver.datadam.sso.api.model.DDSsoIdentity;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.regex.Pattern;

/** Verifies the signature and purpose-specific claims of DataDam JWTs. */
public class DDSsoTokenVerifier {
    static final int MAX_TOKEN_LENGTH = 16_384;
    private static final int MAX_CLOCK_SKEW_SECONDS = 30;
    private static final Pattern INTEGER_DATE = Pattern.compile("-?[0-9]+");
    private static final Logger log = LoggerFactory.getLogger(DDSsoTokenVerifier.class);
    @NotNull
    private final DDSsoClientConfig properties;
    @NotNull
    private final DDSsoJwksCache keys;
    @NotNull
    private final Clock clock;

    public DDSsoTokenVerifier(@NotNull DDSsoClientConfig properties, @NotNull DDSsoJwksCache keys, @NotNull Clock clock) {
        this.properties = properties;
        this.keys = keys;
        this.clock = clock;
    }

    @NotNull
    public DDSsoIdentity verifyIdentity(@NotNull String encoded) throws DDSsoClientException {
        try {
            JsonObject claims = verifiedClaims(encoded);
            if (!matchesAudience(claims.get(DDSsoConstants.CLAIM_AUDIENCE), properties.clientId())
                || !DDSsoConstants.TOKEN_USE_IDENTITY.equals(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_TOKEN_USE)))
                || !properties.identitySource().equals(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_SOURCE)))) {
                throw new IllegalArgumentException();
            }
            Instant issuedAt = date(claims.get(DDSsoConstants.CLAIM_ISSUED_AT));
            Instant authTime = date(claims.get(DDSsoConstants.CLAIM_AUTH_TIME));
            Instant mfaVerifiedAt = claims.has(DDSsoConstants.CLAIM_MFA_VERIFIED_AT)
                ? Instant.parse(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_MFA_VERIFIED_AT))) : null;
            Instant now = clock.instant();
            if (mfaVerifiedAt != null && (mfaVerifiedAt.isBefore(authTime)
                || mfaVerifiedAt.truncatedTo(ChronoUnit.SECONDS).isAfter(issuedAt) || mfaVerifiedAt.isAfter(now))) {
                throw new IllegalArgumentException();
            }
            return new DDSsoIdentity(
                DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_SUBJECT)),
                DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_EMAIL)),
                properties.identitySource(), authTime, mfaVerifiedAt);
        } catch (Exception e) {
            // Tokens, upstream bodies and key data must not appear in diagnostics.
            log.warn("SSO client identity verification failed: reason=invalid_identity_token, exceptionType={}",
                e.getClass().getSimpleName());
            throw new DDSsoClientException("Invalid SSO identity token");
        }
    }

    @NotNull
    public DDSsoAccessIdentity verifyAccessToken(@NotNull String encoded) throws DDSsoClientException {
        try {
            JsonObject claims = verifiedClaims(encoded);
            if (!matchesAudience(claims.get(DDSsoConstants.CLAIM_AUDIENCE), DDSsoConstants.ACCESS_AUDIENCE)
                || !DDSsoConstants.TOKEN_USE_ACCESS.equals(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_TOKEN_USE)))
                || !properties.identitySource().equals(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_SOURCE)))
                || !DDSsoConstants.DESKTOP_CLIENT_ID.equals(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_CLIENT_ID)))) {
                throw new IllegalArgumentException();
            }
            return new DDSsoAccessIdentity(
                DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_SUBJECT)),
                DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_EMAIL)),
                properties.identitySource(), DDSsoConstants.DESKTOP_CLIENT_ID,
                date(claims.get(DDSsoConstants.CLAIM_AUTH_TIME)));
        } catch (Exception e) {
            throw new DDSsoClientException("Invalid SSO access token");
        }
    }

    @NotNull
    private JsonObject verifiedClaims(@NotNull String encoded) throws Exception {
        if (encoded.length() > MAX_TOKEN_LENGTH) {
            throw new IllegalArgumentException();
        }
        String[] parts = encoded.split("\\.", -1);
        if (parts.length != 3) {
            throw new IllegalArgumentException();
        }
        JsonObject header = DDSsoJson.object(DDSsoJson.utf8(DDSsoJson.base64Url(parts[0])));
        byte[] payload = DDSsoJson.base64Url(parts[1]);
        byte[] signatureBytes = DDSsoJson.base64Url(parts[2]);
        JsonElement critical = header.get(DDSsoConstants.JOSE_CRITICAL);
        if (!DDSsoConstants.ALGORITHM_ED25519.equals(DDSsoJson.text(header.get(DDSsoConstants.JOSE_ALGORITHM)))
            || !DDSsoConstants.JOSE_TYPE_JWT.equals(DDSsoJson.text(header.get(DDSsoConstants.JOSE_TYPE)))
            || header.has(DDSsoConstants.JOSE_BASE64_PAYLOAD)
                && !new JsonPrimitive(true).equals(header.get(DDSsoConstants.JOSE_BASE64_PAYLOAD))
            || critical != null && (!(critical instanceof JsonArray array) || !array.isEmpty())
            || signatureBytes.length != DDSsoConstants.ED25519_SIGNATURE_BYTES) {
            throw new IllegalArgumentException();
        }
        Signature signature = Signature.getInstance(DDSsoConstants.ALGORITHM_ED25519);
        signature.initVerify(keys.getPublicKey(DDSsoJson.text(header.get(DDSsoConstants.JOSE_KEY_ID))));
        // JWS signs the original encoded segments, not reserialized JSON.
        signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        if (!signature.verify(signatureBytes)) {
            throw new IllegalArgumentException();
        }
        JsonObject claims = DDSsoJson.object(DDSsoJson.utf8(payload));
        if (!properties.issuer().equals(DDSsoJson.text(claims.get(DDSsoConstants.CLAIM_ISSUER)))) {
            throw new IllegalArgumentException();
        }
        Instant issuedAt = date(claims.get(DDSsoConstants.CLAIM_ISSUED_AT));
        Instant expiresAt = date(claims.get(DDSsoConstants.CLAIM_EXPIRATION_TIME));
        Instant authTime = date(claims.get(DDSsoConstants.CLAIM_AUTH_TIME));
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt) || issuedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))
            || authTime.isAfter(issuedAt)
            || claims.has(DDSsoConstants.CLAIM_NOT_BEFORE) && date(claims.get(DDSsoConstants.CLAIM_NOT_BEFORE)).isAfter(now)) {
            throw new IllegalArgumentException();
        }
        return claims;
    }

    @NotNull
    private static Instant date(@Nullable JsonElement value) {
        if (!(value instanceof JsonPrimitive primitive) || !primitive.isNumber()
            || !INTEGER_DATE.matcher(primitive.getAsString()).matches()) {
            throw new IllegalArgumentException();
        }
        long seconds = Long.parseLong(primitive.getAsString());
        if (seconds < 0) {
            throw new IllegalArgumentException();
        }
        return Instant.ofEpochSecond(seconds);
    }

    private static boolean matchesAudience(@Nullable JsonElement value, @NotNull String expected) {
        if (value instanceof JsonArray array) {
            return array.size() == 1 && expected.equals(DDSsoJson.text(array.get(0)));
        }
        return expected.equals(DDSsoJson.text(value));
    }
}
