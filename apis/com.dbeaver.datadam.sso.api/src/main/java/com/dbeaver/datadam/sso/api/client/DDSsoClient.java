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

import com.dbeaver.datadam.sso.api.exception.DDSsoClientException;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoIdentity;
import com.dbeaver.datadam.sso.api.model.DDSsoJwksResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenResponse;
import com.google.gson.Gson;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jwt.SignedJWT;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.jkiss.code.NotNull;
import org.jkiss.utils.GsonUtils;

import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DDSsoClient {
    private final DDSsoClientConfig properties;
    private final Clock clock;
    private final Gson gson = GsonUtils.gsonBuilder().registerTypeAdapter(Instant.class, new GsonUtils.InstantIsoAdapter()).create();
    private Map<String, PublicKey> keys = Map.of();
    private Instant keysExpireAt = Instant.EPOCH;

    public DDSsoClient(@NotNull DDSsoClientConfig properties) {
        this(properties, Clock.systemUTC());
    }

    public DDSsoClient(@NotNull DDSsoClientConfig properties, @NotNull Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    @NotNull
    public URI endpoint(@NotNull String path) {
        return URI.create(properties.issuer() + (properties.issuer().endsWith("/") ? "" : "/") + path);
    }

    @NotNull
    public DDSsoIdentity exchange(@NotNull String code, @NotNull String verifier) throws DDSsoClientException {
        DDSsoTokenRequest request = new DDSsoTokenRequest(code, verifier, properties.clientId(), properties.redirectUri());
        String form = gson.toJsonTree(request).getAsJsonObject().entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().getAsString())).collect(Collectors.joining("&"));
        String body = send("token", "application/x-www-form-urlencoded", form);
        try {
            DDSsoTokenResponse token = gson.fromJson(body, DDSsoTokenResponse.class);
            if (token == null || token.idToken() == null || token.idToken().length() > 16384 || token.expiresIn() <= 0) {
                throw new IllegalArgumentException();
            }
            return verifyIdentity(token.idToken());
        } catch (RuntimeException e) {
            throw new DDSsoClientException("Invalid SSO token response");
        }
    }

    @NotNull
    public String createHandoff(@NotNull DDSsoHandoffRequest request) throws DDSsoClientException {
        String body = send("handoff", "application/json", gson.toJson(request));
        try {
            DDSsoHandoffResponse handoff = gson.fromJson(body, DDSsoHandoffResponse.class);
            if (handoff == null || handoff.handoff() == null || !handoff.handoff().matches("[A-Za-z0-9_-]{43}")
                || handoff.expiresIn() <= 0) {
                throw new IllegalArgumentException();
            }
            return endpoint("handoff") + "?handoff=" + handoff.handoff();
        } catch (RuntimeException e) {
            throw new DDSsoClientException("Invalid SSO handoff response");
        }
    }

    @NotNull
    public DDSsoIdentity verifyIdentity(@NotNull String encoded) throws DDSsoClientException {
        try {
            if (encoded.length() > 16384) {
                throw new IllegalArgumentException();
            }
            SignedJWT token = SignedJWT.parse(encoded);
            var header = token.getHeader();
            if (!JWSAlgorithm.Ed25519.equals(header.getAlgorithm()) || !JOSEObjectType.JWT.equals(header.getType())
                || header.getKeyID() == null || header.getKeyID().isBlank() || !header.isBase64URLEncodePayload()
                || header.getCriticalParams() != null && !header.getCriticalParams().isEmpty()
                || token.getSignature().decode().length != 64) {
                throw new IllegalArgumentException();
            }
            Signature signature = Signature.getInstance("Ed25519");
            signature.initVerify(publicKey(header.getKeyID()));
            signature.update(token.getSigningInput());
            if (!signature.verify(token.getSignature().decode())) {
                throw new IllegalArgumentException();
            }
            Map<String, Object> claims = token.getPayload().toJSONObject();
            if (claims == null || !properties.issuer().equals(claims.get("iss"))
                || !(properties.clientId().equals(claims.get("aud")) || List.of(properties.clientId()).equals(claims.get("aud")))
                || !"identity".equals(claims.get("token_use")) || !properties.identitySource().equals(claims.get("source"))) {
                throw new IllegalArgumentException();
            }
            Instant issuedAt = date(claims.get("iat"));
            Instant expiresAt = date(claims.get("exp"));
            Instant authTime = date(claims.get("auth_time"));
            Instant now = clock.instant();
            if (!expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt) || issuedAt.isAfter(now.plusSeconds(30))
                || authTime.isAfter(issuedAt) || claims.containsKey("nbf") && date(claims.get("nbf")).isAfter(now)) {
                throw new IllegalArgumentException();
            }
            return new DDSsoIdentity(text(claims.get("sub")), text(claims.get("email")), properties.identitySource(), authTime);
        } catch (Exception e) {
            // Tokens, upstream bodies and key data must not appear in diagnostics.
            throw new DDSsoClientException("Invalid SSO identity token");
        }
    }

    private synchronized PublicKey publicKey(String kid) throws Exception {
        Instant now = clock.instant();
        if (!keysExpireAt.isAfter(now) || !keys.containsKey(kid)) {
            String body = send(".well-known/jwks.json", null, null);
            // Validate JOSE structure before DTO mapping, which deliberately omits private key fields.
            if (JWKSet.parse(body).getKeys().stream().anyMatch(JWK::isPrivate)) {
                throw new IllegalArgumentException();
            }
            DDSsoJwksResponse set = gson.fromJson(body, DDSsoJwksResponse.class);
            if (set == null || set.keys().isEmpty() || set.keys().size() > 16) {
                throw new IllegalArgumentException();
            }
            Map<String, PublicKey> loaded = new HashMap<>();
            for (var key : set.keys()) {
                if (!"OKP".equals(key.keyType()) || !"Ed25519".equals(key.curve())
                    || !"Ed25519".equals(key.algorithm()) || !"sig".equals(key.keyUse())
                    || key.keyId() == null || key.keyId().isBlank() || key.publicKey() == null) {
                    throw new IllegalArgumentException();
                }
                byte[] decoded = Base64.getUrlDecoder().decode(key.publicKey());
                if (decoded.length != 32) {
                    throw new IllegalArgumentException();
                }
                PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(
                    SubjectPublicKeyInfoFactory.createSubjectPublicKeyInfo(new Ed25519PublicKeyParameters(decoded)).getEncoded()
                ));
                if (loaded.putIfAbsent(key.keyId(), publicKey) != null) {
                    throw new IllegalArgumentException();
                }
            }
            keys = Map.copyOf(loaded);
            keysExpireAt = now.plus(properties.jwksCacheTtl());
        }
        PublicKey key = keys.get(kid);
        if (key == null) {
            throw new IllegalArgumentException();
        }
        return key;
    }

    private String send(String path, String contentType, String payload) throws DDSsoClientException {
        HttpURLConnection connection = null;
        try {
            String base = properties.backchannelUrl() != null && !properties.backchannelUrl().isBlank()
                ? properties.backchannelUrl() : properties.issuer();
            URI uri = URI.create(base + (base.endsWith("/") ? "" : "/") + path);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(10000);
            connection.setInstanceFollowRedirects(false);
            if (payload != null) {
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", contentType);
                connection.setRequestProperty("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                    (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8)
                ));
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) {
                    output.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (connection.getResponseCode() != 200) {
                throw new DDSsoClientException("SSO request failed");
            }
            try (var body = connection.getInputStream()) {
                byte[] bytes = body.readNBytes(1_048_577);
                if (bytes.length > 1_048_576) {
                    throw new DDSsoClientException("SSO response is too large");
                }
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (IOException e) {
            throw new DDSsoClientException("SSO service is unavailable");
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static String text(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException();
        }
        return text;
    }

    private static Instant date(Object value) {
        if (!(value instanceof Long || value instanceof Integer) || ((Number) value).longValue() < 0) {
            throw new IllegalArgumentException();
        }
        return Instant.ofEpochSecond(((Number) value).longValue());
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
