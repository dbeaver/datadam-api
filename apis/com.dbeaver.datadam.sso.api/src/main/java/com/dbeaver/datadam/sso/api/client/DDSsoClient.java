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
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoIdentity;
import com.dbeaver.datadam.sso.api.model.DDSsoJwksResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenResponse;
import com.google.gson.Gson;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jwt.JWTClaimNames;
import com.nimbusds.jwt.SignedJWT;
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters;
import org.bouncycastle.crypto.util.SubjectPublicKeyInfoFactory;
import org.jkiss.code.NotNull;
import org.jkiss.utils.GsonUtils;
import org.jkiss.utils.HttpConstants;

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
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DDSsoClient {
    private static final String HTTP_POST = "POST";
    private static final int CONNECT_TIMEOUT_MILLIS = 5_000;
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private static final int MAX_RESPONSE_BYTES = 1_048_576;
    private static final int MAX_TOKEN_LENGTH = 16_384;
    private static final int MAX_JWKS_KEYS = 16;
    private static final int MAX_CLOCK_SKEW_SECONDS = 30;
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
        return endpoint(properties.issuer(), path);
    }

    private static URI endpoint(String base, String path) {
        return URI.create(base + (base.endsWith("/") ? "" : "/") + (path.startsWith("/") ? path.substring(1) : path));
    }

    @NotNull
    public DDSsoIdentity exchange(@NotNull String code, @NotNull String verifier) throws DDSsoClientException {
        DDSsoTokenRequest request = new DDSsoTokenRequest(code, verifier, properties.clientId(), properties.redirectUri());
        String form = gson.toJsonTree(request).getAsJsonObject().entrySet().stream()
            .map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue().getAsString())).collect(Collectors.joining("&"));
        String body = send(DDSsoConstants.TOKEN_PATH, HttpConstants.CONTENT_TYPE_APP_FORM, form);
        try {
            DDSsoTokenResponse token = gson.fromJson(body, DDSsoTokenResponse.class);
            if (token == null || token.idToken() == null || token.idToken().length() > MAX_TOKEN_LENGTH || token.expiresIn() <= 0) {
                throw new IllegalArgumentException();
            }
            return verifyIdentity(token.idToken());
        } catch (RuntimeException e) {
            throw new DDSsoClientException("Invalid SSO token response");
        }
    }

    @NotNull
    public String createHandoff(@NotNull DDSsoHandoffRequest request) throws DDSsoClientException {
        String body = send(DDSsoConstants.HANDOFF_PATH, HttpConstants.CONTENT_TYPE_JSON, gson.toJson(request));
        try {
            DDSsoHandoffResponse handoff = gson.fromJson(body, DDSsoHandoffResponse.class);
            if (handoff == null || handoff.handoff() == null || !DDSsoConstants.BASE64URL_256_PATTERN.matcher(handoff.handoff()).matches()
                || handoff.expiresIn() <= 0) {
                throw new IllegalArgumentException();
            }
            return endpoint(DDSsoConstants.HANDOFF_PATH) + "?" + DDSsoConstants.PARAM_HANDOFF + "=" + handoff.handoff();
        } catch (RuntimeException e) {
            throw new DDSsoClientException("Invalid SSO handoff response");
        }
    }

    @NotNull
    public DDSsoIdentity verifyIdentity(@NotNull String encoded) throws DDSsoClientException {
        try {
            if (encoded.length() > MAX_TOKEN_LENGTH) {
                throw new IllegalArgumentException();
            }
            SignedJWT token = SignedJWT.parse(encoded);
            var header = token.getHeader();
            if (!JWSAlgorithm.Ed25519.equals(header.getAlgorithm()) || !JOSEObjectType.JWT.equals(header.getType())
                || header.getKeyID() == null || header.getKeyID().isBlank() || !header.isBase64URLEncodePayload()
                || header.getCriticalParams() != null && !header.getCriticalParams().isEmpty()
                || token.getSignature().decode().length != DDSsoConstants.ED25519_SIGNATURE_BYTES) {
                throw new IllegalArgumentException();
            }
            Signature signature = Signature.getInstance(DDSsoConstants.ALGORITHM_ED25519);
            signature.initVerify(publicKey(header.getKeyID()));
            signature.update(token.getSigningInput());
            if (!signature.verify(token.getSignature().decode())) {
                throw new IllegalArgumentException();
            }
            Map<String, Object> claims = token.getPayload().toJSONObject();
            if (claims == null || !properties.issuer().equals(claims.get(JWTClaimNames.ISSUER))
                || !(properties.clientId().equals(claims.get(JWTClaimNames.AUDIENCE))
                    || List.of(properties.clientId()).equals(claims.get(JWTClaimNames.AUDIENCE)))
                || !DDSsoConstants.TOKEN_USE_IDENTITY.equals(claims.get(DDSsoConstants.CLAIM_TOKEN_USE))
                || !properties.identitySource().equals(claims.get(DDSsoConstants.CLAIM_SOURCE))) {
                throw new IllegalArgumentException();
            }
            Instant issuedAt = date(claims.get(JWTClaimNames.ISSUED_AT));
            Instant expiresAt = date(claims.get(JWTClaimNames.EXPIRATION_TIME));
            Instant authTime = date(claims.get(DDSsoConstants.CLAIM_AUTH_TIME));
            Instant mfaVerifiedAt = claims.containsKey(DDSsoConstants.CLAIM_MFA_VERIFIED_AT)
                ? preciseInstant(claims.get(DDSsoConstants.CLAIM_MFA_VERIFIED_AT)) : null;
            Instant now = clock.instant();
            if (!expiresAt.isAfter(now) || !expiresAt.isAfter(issuedAt) || issuedAt.isAfter(now.plusSeconds(MAX_CLOCK_SKEW_SECONDS))
                || authTime.isAfter(issuedAt)
                || mfaVerifiedAt != null && (mfaVerifiedAt.isBefore(authTime)
                    || mfaVerifiedAt.truncatedTo(ChronoUnit.SECONDS).isAfter(issuedAt) || mfaVerifiedAt.isAfter(now))
                || claims.containsKey(JWTClaimNames.NOT_BEFORE) && date(claims.get(JWTClaimNames.NOT_BEFORE)).isAfter(now)) {
                throw new IllegalArgumentException();
            }
            return new DDSsoIdentity(text(claims.get(JWTClaimNames.SUBJECT)), text(claims.get(DDSsoConstants.CLAIM_EMAIL)),
                properties.identitySource(), authTime, mfaVerifiedAt);
        } catch (Exception e) {
            // Tokens, upstream bodies and key data must not appear in diagnostics.
            throw new DDSsoClientException("Invalid SSO identity token");
        }
    }

    private synchronized PublicKey publicKey(String kid) throws Exception {
        Instant now = clock.instant();
        if (!keysExpireAt.isAfter(now) || !keys.containsKey(kid)) {
            String body = send(DDSsoConstants.JWKS_PATH, null, null);
            // Validate JOSE structure before DTO mapping, which deliberately omits private key fields.
            if (JWKSet.parse(body).getKeys().stream().anyMatch(JWK::isPrivate)) {
                throw new IllegalArgumentException();
            }
            DDSsoJwksResponse set = gson.fromJson(body, DDSsoJwksResponse.class);
            if (set == null || set.keys().isEmpty() || set.keys().size() > MAX_JWKS_KEYS) {
                throw new IllegalArgumentException();
            }
            Map<String, PublicKey> loaded = new HashMap<>();
            for (var key : set.keys()) {
                if (!KeyType.OKP.toString().equals(key.keyType()) || !Curve.Ed25519.toString().equals(key.curve())
                    || !DDSsoConstants.ALGORITHM_ED25519.equals(key.algorithm()) || !KeyUse.SIGNATURE.toString().equals(key.keyUse())
                    || key.keyId() == null || key.keyId().isBlank() || key.publicKey() == null) {
                    throw new IllegalArgumentException();
                }
                byte[] decoded = Base64.getUrlDecoder().decode(key.publicKey());
                if (decoded.length != DDSsoConstants.ED25519_PUBLIC_KEY_BYTES) {
                    throw new IllegalArgumentException();
                }
                PublicKey publicKey = KeyFactory.getInstance(DDSsoConstants.ALGORITHM_ED25519).generatePublic(new X509EncodedKeySpec(
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
            URI uri = endpoint(base, path);
            connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
            connection.setReadTimeout(READ_TIMEOUT_MILLIS);
            connection.setInstanceFollowRedirects(false);
            if (payload != null) {
                connection.setRequestMethod(HTTP_POST);
                connection.setRequestProperty(HttpConstants.HEADER_CONTENT_TYPE, contentType);
                connection.setRequestProperty(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.BASIC_PREFIX + Base64.getEncoder().encodeToString(
                    (properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8)
                ));
                connection.setDoOutput(true);
                try (var output = connection.getOutputStream()) {
                    output.write(payload.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (connection.getResponseCode() != HttpConstants.CODE_OK) {
                throw new DDSsoClientException("SSO request failed");
            }
            try (var body = connection.getInputStream()) {
                byte[] bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (bytes.length > MAX_RESPONSE_BYTES) {
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

    private static Instant preciseInstant(Object value) {
        if (!(value instanceof String timestamp)) {
            throw new IllegalArgumentException();
        }
        return Instant.parse(timestamp);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
