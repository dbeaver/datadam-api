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
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.jkiss.code.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Validates and caches public keys obtained from the configured SSO endpoint. */
public class DDSsoJwksCache {
    private static final Logger log = LoggerFactory.getLogger(DDSsoJwksCache.class);
    private static final int MAX_JWKS_KEYS = 16;
    private static final Duration MAX_REFRESH_COOLDOWN = Duration.ofSeconds(30);
    private static final Set<String> PRIVATE_KEY_FIELDS = Set.of("d", "p", "q", "dp", "dq", "qi", "oth", "k");
    @NotNull
    private final DDSsoClient client;
    @NotNull
    private final Duration ttl;
    @NotNull
    private final Duration refreshCooldown;
    @NotNull
    private final Clock clock;
    @NotNull
    private Map<String, PublicKey> keys = Map.of();
    @NotNull
    private Instant expiresAt = Instant.EPOCH;
    @NotNull
    private Instant nextRefreshAllowedAt = Instant.MIN;

    public DDSsoJwksCache(@NotNull DDSsoClient client, @NotNull Duration ttl, @NotNull Clock clock) {
        this.client = client;
        this.ttl = ttl;
        this.refreshCooldown = ttl.compareTo(MAX_REFRESH_COOLDOWN) < 0 ? ttl : MAX_REFRESH_COOLDOWN;
        this.clock = clock;
    }

    @NotNull
    public synchronized PublicKey getPublicKey(@NotNull String kid) throws DDSsoClientException {
        Instant now = clock.instant();
        if (!expiresAt.isAfter(now) || !keys.containsKey(kid)) {
            if (now.isBefore(nextRefreshAllowedAt)) {
                throw new DDSsoClientException("SSO signing key unavailable");
            }
            // Throttle all key IDs together, including attempts that fail to load or parse JWKS.
            nextRefreshAllowedAt = now.plus(refreshCooldown);
            String body = client.getJwks();
            try {
                keys = parseKeys(body);
            } catch (IOException | GeneralSecurityException | RuntimeException e) {
                throw new DDSsoClientException("Invalid SSO public keys");
            }
            expiresAt = now.plus(ttl);
            log.info("SSO client signing keys refreshed");
        }
        PublicKey key = keys.get(kid);
        if (key == null) {
            throw new DDSsoClientException("Unknown SSO signing key");
        }
        return key;
    }

    @NotNull
    private static Map<String, PublicKey> parseKeys(@NotNull String body) throws IOException, GeneralSecurityException {
        JsonElement value = DDSsoJson.object(body).get(DDSsoConstants.JWKS_KEYS);
        if (!(value instanceof JsonArray keySet) || keySet.isEmpty() || keySet.size() > MAX_JWKS_KEYS) {
            throw new IllegalArgumentException();
        }
        Map<String, PublicKey> loaded = new HashMap<>();
        for (JsonElement element : keySet) {
            if (!(element instanceof JsonObject key) || PRIVATE_KEY_FIELDS.stream().anyMatch(key::has)
                || !DDSsoConstants.JWK_TYPE_OKP.equals(DDSsoJson.text(key.get(DDSsoConstants.JWK_KEY_TYPE)))
                || !DDSsoConstants.ALGORITHM_ED25519.equals(DDSsoJson.text(key.get(DDSsoConstants.JWK_CURVE)))
                || !DDSsoConstants.ALGORITHM_ED25519.equals(DDSsoJson.text(key.get(DDSsoConstants.JOSE_ALGORITHM)))
                || !DDSsoConstants.JWK_USE_SIGNATURE.equals(DDSsoJson.text(key.get(DDSsoConstants.JWK_USE)))) {
                throw new IllegalArgumentException();
            }
            String keyId = DDSsoJson.text(key.get(DDSsoConstants.JOSE_KEY_ID));
            byte[] decoded = DDSsoJson.base64Url(DDSsoJson.text(key.get(DDSsoConstants.JWK_PUBLIC_KEY)));
            if (decoded.length != DDSsoConstants.ED25519_PUBLIC_KEY_BYTES) {
                throw new IllegalArgumentException();
            }
            PublicKey publicKey = KeyFactory.getInstance(DDSsoConstants.ALGORITHM_ED25519).generatePublic(new X509EncodedKeySpec(
                SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(new Ed25519PublicKeyParameters(decoded)).getEncoded()));
            if (loaded.putIfAbsent(keyId, publicKey) != null) {
                throw new IllegalArgumentException();
            }
        }
        return Map.copyOf(loaded);
    }
}
