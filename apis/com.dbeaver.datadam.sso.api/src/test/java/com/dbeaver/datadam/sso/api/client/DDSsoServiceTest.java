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
import com.dbeaver.datadam.sso.api.DDSsoConstants;
import org.assertj.core.api.Assertions;
import com.dbeaver.datadam.sso.api.model.DDSsoAuthorizeRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoIdentity;
import com.dbeaver.datadam.sso.api.model.DDSsoJwksResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoPublicJwk;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoAccessIdentity;
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

class DDSsoServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-01T12:00:00Z");
    private final Gson gson = new Gson();
    private final AtomicInteger jwksRequests = new AtomicInteger();
    private final AtomicReference<String> keysJson = new AtomicReference<>();
    private final AtomicReference<String> responseJson = new AtomicReference<>();
    private final AtomicReference<String> posted = new AtomicReference<>();
    private final AtomicReference<String> credentials = new AtomicReference<>();
    private final AtomicReference<String> contentType = new AtomicReference<>();
    private final AtomicReference<String> requestMethod = new AtomicReference<>();
    private final AtomicReference<String> requestPath = new AtomicReference<>();
    private final AtomicReference<String> jwksMethod = new AtomicReference<>();
    private final AtomicInteger responseStatus = new AtomicInteger(200);
    private final AtomicReference<String> redirect = new AtomicReference<>();
    private HttpServer server;
    private KeyPair keyPair;
    private DDSsoClient http;
    private DDSsoService service;
    private DDSsoTokenVerifier verifier;

    @BeforeEach
    void setUp() throws Exception {
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keysJson.set(jwks("key1", keyPair));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/jwks.json", exchange -> {
            jwksRequests.incrementAndGet();
            jwksMethod.set(exchange.getRequestMethod());
            byte[] bytes = keysJson.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/", exchange -> {
            credentials.set(exchange.getRequestHeaders().getFirst("Authorization"));
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestMethod.set(exchange.getRequestMethod());
            requestPath.set(exchange.getRequestURI().getPath());
            posted.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = responseJson.get().getBytes(StandardCharsets.UTF_8);
            if (redirect.get() != null) {
                exchange.getResponseHeaders().set("Location", redirect.get());
            }
            exchange.sendResponseHeaders(responseStatus.get(), bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        DDSsoClientConfig config = new DDSsoClientConfig("https://sso.example.com",
            "http://127.0.0.1:" + server.getAddress().getPort(), "account", "test-secret",
            "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5));
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        http = new DDSsoClient(config);
        verifier = new DDSsoTokenVerifier(config, new DDSsoJwksCache(http, config.jwksCacheTtl(), clock), clock);
        service = new DDSsoService(config, http, verifier);
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @ParameterizedTest
    @CsvSource({
        "https://sso.example.com, https://sso.example.com/authorize",
        "https://sso.example.com/, https://sso.example.com/authorize",
        "https://sso.example.com/issuer, https://sso.example.com/issuer/authorize",
        "https://sso.example.com/issuer/, https://sso.example.com/issuer/authorize"
    })
    void joinsSharedPathsWithoutLosingIssuerPathOrDuplicatingSlashes(@NotNull String issuer, @NotNull String expected) {
        var configured = new DDSsoClient(new DDSsoClientConfig(issuer, "", "account", "test-secret",
            "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5)));
        Assertions.assertThat(configured.endpoint(DDSsoConstants.AUTHORIZE_PATH).toString()).isEqualTo(expected);
        Assertions.assertThat(configured.endpoint("authorize").toString()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal", "/internal/"})
    void preservesBackchannelPathWithSharedEndpointPaths(@NotNull String path) throws Exception {
        var config = new DDSsoClientConfig("https://sso.example.com/issuer/",
            "http://127.0.0.1:" + server.getAddress().getPort() + path, "account", "test-secret",
            "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5));
        var configured = new DDSsoService(config, new DDSsoClient(config), verifier);
        responseJson.set(gson.toJson(Map.of("handoff", "A".repeat(43), "expires_in", 60)));
        var request = new DDSsoHandoffRequest(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW),
            new DDSsoAuthorizeRequest("account", "https://account.example.com/sso/callback", "state", "A".repeat(43), "S256"), "B".repeat(43));
        Assertions.assertThat(configured.createHandoff(request)).isEqualTo("https://sso.example.com/issuer/handoff?handoff=" + "A".repeat(43));
        Assertions.assertThat(requestPath).hasValue("/internal/handoff");
    }

    @Test
    void acceptsOnlyDesktopAccessTokensWithApiAudienceAndAccessPurpose() throws Exception {
        var claims = claims();
        claims.put("aud", DDSsoConstants.ACCESS_AUDIENCE);
        claims.put("token_use", DDSsoConstants.TOKEN_USE_ACCESS);
        claims.put("client_id", DDSsoConstants.DESKTOP_CLIENT_ID);
        String access = signed(claims, header("key1"));
        Assertions.assertThat(verifier.verifyAccessToken(access)).isEqualTo(new DDSsoAccessIdentity(
            "user-id", "user@example.com", "datadam-account", DDSsoConstants.DESKTOP_CLIENT_ID, NOW.minusSeconds(60)));
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(access)).isInstanceOf(DDSsoClientException.class);
        for (String name : List.of("aud", "token_use", "client_id")) {
            var modified = new HashMap<>(claims);
            modified.put(name, "unexpected");
            String invalid = signed(modified, header("key1"));
            Assertions.assertThatThrownBy(() -> verifier.verifyAccessToken(invalid)).isInstanceOf(DDSsoClientException.class);
        }
        Assertions.assertThatThrownBy(() -> verifier.verifyAccessToken(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @Test
    void validatesTokenAndCachesTrustedJwks() throws Exception {
        String token = signed(claims(), header("key1"));
        var first = verifier.verifyIdentity(token);
        Assertions.assertThat(first).isEqualTo(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW.minusSeconds(60)));
        Assertions.assertThat(verifier.verifyIdentity(token)).isEqualTo(first);
        Assertions.assertThat(jwksRequests).hasValue(1);
        Assertions.assertThat(jwksMethod).hasValue("GET");
    }

    @Test
    void verifiesOriginalEncodedSegmentsWithWhitespaceUnicodeAndArrayAudience() throws Exception {
        Map<String, Object> claims = claims();
        claims.put("aud", List.of("account"));
        claims.put("sub", "пользователь");
        String header = "{ \"kid\": \"key1\", \"typ\": \"JWT\", \"alg\": \"Ed25519\", \"b64\": true }";
        String payload = "\n  " + gson.toJson(claims) + "\n";
        Assertions.assertThat(verifier.verifyIdentity(signed(payload, header)).subject()).isEqualTo("пользователь");
    }

    @ParameterizedTest
    @MethodSource("invalidHeaders")
    void rejectsUnsupportedOrIncorrectlyTypedHeadersBeforeJwksLookup(@NotNull String field, @NotNull Object value) throws Exception {
        Map<String, Object> header = header("key1");
        header.put(field, value);
        String token = signed(claims(), header);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(token)).isInstanceOf(DDSsoClientException.class);
        Assertions.assertThat(jwksRequests).hasValue(0);
    }

    @NotNull
    static Stream<Arguments> invalidHeaders() {
        return Stream.of(
            Arguments.of("alg", "none"), Arguments.of("alg", "EdDSA"), Arguments.of("alg", "RS256"),
            Arguments.of("alg", 123), Arguments.of("typ", "other"), Arguments.of("typ", true),
            Arguments.of("kid", ""), Arguments.of("kid", " "), Arguments.of("kid", 123),
            Arguments.of("b64", false), Arguments.of("b64", "true"), Arguments.of("b64", 1),
            Arguments.of("crit", List.of("unknown")), Arguments.of("crit", "unknown"), Arguments.of("crit", List.of(123))
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ".", "..", "a.b.c", "a.b.c.d", "a.b.c.d.e"})
    void rejectsMalformedCompactTokens(@NotNull String token) {
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(token)).isInstanceOf(DDSsoClientException.class);
        Assertions.assertThat(jwksRequests).hasValue(0);
    }

    @Test
    void rejectsPaddedAndNonCanonicalBase64AndIncorrectSignatureSizes() throws Exception {
        String token = signed(claims(), header("key1"));
        // The last base64url character of a 64-byte signature has four unused bits.
        String nonCanonical = token.substring(0, token.length() - 1) + (char) (token.charAt(token.length() - 1) + 1);
        for (String invalid : List.of(token + "==", nonCanonical,
            token.substring(0, token.lastIndexOf('.') + 1) + encode(new byte[63]),
            token.substring(0, token.lastIndexOf('.') + 1) + encode(new byte[65]))) {
            Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(invalid)).isInstanceOf(DDSsoClientException.class);
        }
        Assertions.assertThat(jwksRequests).hasValue(0);
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "\"other\""})
    void rejectsDuplicateHeaderAndClaimMembersEvenWhenTheLastValueIsValid(@NotNull String firstValue) throws Exception {
        String header = gson.toJson(header("key1"));
        String payload = gson.toJson(claims());
        String duplicateHeader = "{\"alg\":" + firstValue + "," + header.substring(1);
        String duplicateClaims = "{\"sub\":" + firstValue + "," + payload.substring(1);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(payload, duplicateHeader)))
            .isInstanceOf(DDSsoClientException.class);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(duplicateClaims, header)))
            .isInstanceOf(DDSsoClientException.class);
    }

    @Test
    void rejectsLenientJsonTrailingDataAndWrongRootTypes() throws Exception {
        String header = gson.toJson(header("key1"));
        String payload = gson.toJson(claims());
        for (String invalidHeader : List.of("/*comment*/" + header, header + "{}", header.replace('"', '\''),
            header.substring(0, header.length() - 1) + ",}", "[" + header + "]", "null")) {
            Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(payload, invalidHeader)))
                .isInstanceOf(DDSsoClientException.class);
        }
        for (String invalidPayload : List.of("/*comment*/" + payload, payload + "{}", "[" + payload + "]", "null",
            "{\"extra\":{\"field\":null,\"field\":1}," + payload.substring(1),
            "{\"extra\":" + "[".repeat(40) + "0" + "]".repeat(40) + "," + payload.substring(1))) {
            Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(invalidPayload, header)))
                .isInstanceOf(DDSsoClientException.class);
        }
    }

    @Test
    void rejectsMalformedUtf8InsideSignedJson() throws Exception {
        String header = gson.toJson(header("key1"));
        String payload = gson.toJson(claims());
        byte[] invalidHeader = ("{\"extra\":\"\u00ff\"," + header.substring(1)).getBytes(StandardCharsets.ISO_8859_1);
        byte[] invalidPayload = ("{\"extra\":\"\u00ff\"," + payload.substring(1)).getBytes(StandardCharsets.ISO_8859_1);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signedInput(encode(invalidHeader) + "." + encode(payload))))
            .isInstanceOf(DDSsoClientException.class);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signedInput(encode(header) + "." + encode(invalidPayload))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @Test
    void acceptsSignedMfaProofAndRejectsInvalidAssuranceClaims() throws Exception {
        Map<String, Object> claims = claims();
        Instant verifiedAt = NOW.minusSeconds(20).plusNanos(123_456_000);
        claims.put(DDSsoConstants.CLAIM_MFA_VERIFIED_AT, verifiedAt.toString());
        Assertions.assertThat(verifier.verifyIdentity(signed(claims, header("key1"))))
            .isEqualTo(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW.minusSeconds(60), verifiedAt));
        for (Object invalid : List.of("true", true, -1, NOW.plusSeconds(1).toString(),
            NOW.minusSeconds(61).toString())) {
            claims.put(DDSsoConstants.CLAIM_MFA_VERIFIED_AT, invalid);
            Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims, header("key1"))))
                .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
        }
    }

    @Test
    void rejectsMfaProofAddedAfterIdentityTokenWasSigned() throws Exception {
        Map<String, Object> claims = claims();
        String[] token = signed(claims, header("key1")).split("\\.");
        claims.put(DDSsoConstants.CLAIM_MFA_VERIFIED_AT, NOW.minusSeconds(20).toString());
        String modified = token[0] + "." + encode(gson.toJson(claims)) + "." + token[2];

        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(modified))
            .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
    }

    @Test
    void credentialBearingClientsDoNotFollowRedirects() throws Exception {
        var forwarded = new AtomicInteger();
        HttpServer destination = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        destination.createContext("/", exchange -> {
            forwarded.incrementAndGet();
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        destination.start();
        try {
            responseJson.set("{}");
            responseStatus.set(307);
            redirect.set("http://127.0.0.1:" + destination.getAddress().getPort());
            Assertions.assertThatThrownBy(() -> http.exchange(new DDSsoTokenRequest(
                "code", "verifier", "account", "https://account.example.com/sso/callback")))
                .isInstanceOf(DDSsoClientException.class);
            var account = new DDSsoAccountClient("http://127.0.0.1:" + server.getAddress().getPort(), "s".repeat(32));
            Assertions.assertThatThrownBy(() -> account.exchange("test-access-token"))
                .isInstanceOf(DDSsoClientException.class);
            Assertions.assertThat(forwarded).hasValue(0);
        } finally {
            destination.stop(0);
        }
    }

    @Test
    void httpClientDeserializesTheEntireResponse() throws Exception {
        String token = "t".repeat(1_048_576);
        responseJson.set(gson.toJson(Map.of("id_token", token, "expires_in", 300)));
        var response = http.exchange(new DDSsoTokenRequest(
            "code", "verifier", "account", "https://account.example.com/sso/callback"));
        Assertions.assertThat(response.idToken()).isEqualTo(token);
        Assertions.assertThat(jwksRequests).hasValue(0);
    }

    @Test
    void httpClientReturnsUnverifiedTokenWithoutFetchingSigningKeys() throws Exception {
        responseJson.set(gson.toJson(Map.of("id_token", "opaque-unverified-token", "expires_in", 300)));
        var response = http.exchange(new DDSsoTokenRequest("code", "verifier", "account", "https://account.example.com/sso/callback"));
        Assertions.assertThat(response.idToken()).isEqualTo("opaque-unverified-token");
        Assertions.assertThat(response.expiresIn()).isEqualTo(300);
        Assertions.assertThat(jwksRequests).hasValue(0);
    }

    @Test
    void serviceRejectsExpiredIdentityReturnedBySuccessfulHttpExchange() throws Exception {
        var expired = claims();
        expired.put("exp", NOW.getEpochSecond());
        responseJson.set(gson.toJson(Map.of("id_token", signed(expired, header("key1")), "expires_in", 300)));
        Assertions.assertThatThrownBy(() -> service.exchange("code", "verifier"))
            .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
        Assertions.assertThat(jwksRequests).hasValue(1);
    }

    @Test
    void exchangesCodeAndVerifierOnlyInAuthenticatedFormBody() throws Exception {
        responseJson.set(gson.toJson(Map.of("id_token", signed(claims(), header("key1")), "expires_in", 300)));
        Assertions.assertThat(service.exchange("code+/%", "verifier")).isNotNull();
        Assertions.assertThat(requestMethod).hasValue("POST");
        Assertions.assertThat(requestPath).hasValue("/token");
        Assertions.assertThat(contentType).hasValue("application/x-www-form-urlencoded");
        Assertions.assertThat(credentials.get()).isEqualTo("Basic " + java.util.Base64.getEncoder().encodeToString("account:test-secret".getBytes(StandardCharsets.UTF_8)));
        Map<String, String> form = new HashMap<>();
        for (String pair : posted.get().split("&")) {
            String[] values = pair.split("=", 2);
            form.put(values[0], URLDecoder.decode(values[1], StandardCharsets.UTF_8));
        }
        Assertions.assertThat(form).containsEntry("code", "code+/%").containsEntry("code_verifier", "verifier")
            .containsEntry("client_id", "account").containsEntry("redirect_uri", "https://account.example.com/sso/callback");
        Assertions.assertThat(form).containsOnlyKeys("code", "code_verifier", "client_id", "redirect_uri");
        Assertions.assertThat(gson.fromJson(gson.toJson(form), DDSsoTokenRequest.class)).isEqualTo(new DDSsoTokenRequest(
            "code+/%", "verifier", "account", "https://account.example.com/sso/callback"
        ));
    }

    @Test
    void postsHandoffAndUsesPublicIssuerForBrowserRedirect() throws Exception {
        responseJson.set(gson.toJson(Map.of("handoff", "A".repeat(43), "expires_in", 60)));
        var request = new DDSsoHandoffRequest(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW),
            new DDSsoAuthorizeRequest("account", "https://account.example.com/sso/callback", "state", "A".repeat(43), "S256"), "B".repeat(43));
        Assertions.assertThat(service.createHandoff(request)).isEqualTo("https://sso.example.com/handoff?handoff=" + "A".repeat(43));
        Assertions.assertThat(requestMethod).hasValue("POST");
        Assertions.assertThat(requestPath).hasValue("/handoff");
        Assertions.assertThat(contentType).hasValue("application/json");
        var body = JsonParser.parseString(posted.get()).getAsJsonObject();
        Assertions.assertThat(body.keySet()).containsExactlyInAnyOrder("identity", "authorization", "flow_id");
        Assertions.assertThat(body.get("flow_id").getAsString()).isEqualTo("B".repeat(43));
        Assertions.assertThat(body.getAsJsonObject("identity").get("authTime").getAsString()).isEqualTo(NOW.toString());
    }

    @ParameterizedTest
    @MethodSource("invalidClaims")
    void rejectsInvalidIdentityClaimsEvenWithCorrectSignature(@NotNull String claim, @Nullable Object value) throws Exception {
        Map<String, Object> claims = claims();
        if (value == null) {
            claims.remove(claim);
        } else {
            claims.put(claim, value);
        }
        String token = signed(claims, header("key1"));
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(token)).isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
    }

    @NotNull
    static Stream<Arguments> invalidClaims() {
        return Stream.of(Arguments.of("iss", "https://other.example"), Arguments.of("aud", "admin"),
            Arguments.of("aud", List.of("account", "admin")), Arguments.of("token_use", "sso_session"),
            Arguments.of("source", "another-source"), Arguments.of("sub", ""), Arguments.of("sub", 123),
            Arguments.of("email", null), Arguments.of("email", true), Arguments.of("email", List.of("user@example.com")),
            Arguments.of("aud", List.of()), Arguments.of("aud", 123),
            Arguments.of("exp", NOW.getEpochSecond()), Arguments.of("exp", null), Arguments.of("exp", "9999999999"),
            Arguments.of("exp", NOW.plusSeconds(300).getEpochSecond() + 0.5), Arguments.of("exp", -1),
            Arguments.of("exp", new java.math.BigInteger("9223372036854775808")),
            Arguments.of("exp", new java.math.BigDecimal("1E+20")),
            Arguments.of("iat", NOW.plusSeconds(31).getEpochSecond()), Arguments.of("auth_time", null),
            Arguments.of("auth_time", NOW.plusSeconds(1).getEpochSecond()), Arguments.of("nbf", NOW.plusSeconds(1).getEpochSecond()));
    }

    @Test
    void rejectsChangedSignatureAndUnsupportedHeaders() throws Exception {
        String original = signed(claims(), header("key1"));
        String tampered = original.substring(0, original.lastIndexOf('.') + 1) + encode(new byte[64]);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(tampered)).isInstanceOf(DDSsoClientException.class);
        for (Map<String, Object> header : List.<Map<String, Object>>of(
            Map.of("alg", "HS256", "kid", "key1", "typ", "JWT"), Map.of("alg", "Ed25519", "kid", "key1"))) {
            Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header))).isInstanceOf(DDSsoClientException.class);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"d", "p", "q", "dp", "dq", "qi", "oth", "k"})
    void rejectsPrivateKeyFieldsBeforeMappingToPublicKeyModel(@NotNull String field) throws Exception {
        var response = JsonParser.parseString(keysJson.get()).getAsJsonObject();
        response.getAsJsonArray("keys").get(0).getAsJsonObject().addProperty(field, encode(new byte[32]));
        keysJson.set(gson.toJson(response));
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
    }

    @Test
    void rejectsDuplicatePublicKeyIds() throws Exception {
        var response = gson.fromJson(keysJson.get(), DDSsoJwksResponse.class);
        keysJson.set(gson.toJson(new DDSsoJwksResponse(List.of(response.keys().getFirst(), response.keys().getFirst()))));
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"keys", "kid", "x"})
    void rejectsDuplicateJwksMembersIncludingNullFirstValues(@NotNull String field) throws Exception {
        keysJson.set(keysJson.get().replace("\"" + field + "\":", "\"" + field + "\":null,\"" + field + "\":"));
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"keys\":null}", "{\"keys\":[]}", "{\"keys\":{}}", "{\"keys\":[null]}", "{\"keys\":[1]}"})
    void rejectsMalformedJwks(@NotNull String response) throws Exception {
        keysJson.set(response);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidPublicKeys")
    void rejectsInvalidPublicKeyParameters(@NotNull String field, @Nullable Object value) throws Exception {
        var response = JsonParser.parseString(keysJson.get()).getAsJsonObject();
        response.getAsJsonArray("keys").get(0).getAsJsonObject().add(field, gson.toJsonTree(value));
        keysJson.set(gson.toJson(response));
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @NotNull
    static Stream<Arguments> invalidPublicKeys() {
        return Stream.of(
            Arguments.of("kty", "RSA"), Arguments.of("crv", "X25519"), Arguments.of("alg", "EdDSA"),
            Arguments.of("use", "enc"), Arguments.of("kid", ""), Arguments.of("kid", 123), Arguments.of("kid", true),
            Arguments.of("x", null), Arguments.of("x", encode(new byte[31])), Arguments.of("x", encode(new byte[33])),
            Arguments.of("x", encode(new byte[32]) + "="), Arguments.of("x", 123)
        );
    }

    @Test
    void refreshesUnknownKidFromConfiguredJwksOnly() throws Exception {
        verifier.verifyIdentity(signed(claims(), header("key1")));
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keysJson.set(jwks("key2", keyPair));
        Assertions.assertThat(verifier.verifyIdentity(signed(claims(), header("key2")))).isNotNull();
        Assertions.assertThat(jwksRequests).hasValue(2);
        Assertions.assertThatThrownBy(() -> verifier.verifyIdentity(signed(claims(), header("missing")))).isInstanceOf(DDSsoClientException.class);
    }

    @NotNull
    private String signed(@NotNull Map<String, Object> claims, @NotNull Map<String, Object> header) throws Exception {
        return signed(gson.toJson(claims), gson.toJson(header));
    }

    @NotNull
    private String signed(@NotNull String claimsJson, @NotNull String headerJson) throws Exception {
        return signedInput(encode(headerJson) + "." + encode(claimsJson));
    }

    @NotNull
    private String signedInput(@NotNull String input) throws Exception {
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + encode(signer.sign());
    }

    @NotNull
    private String jwks(@NotNull String kid, @NotNull KeyPair pair) {
        String publicKey = encode(
            SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()).getPublicKeyData().getBytes()
        );
        return gson.toJson(new DDSsoJwksResponse(List.of(
            new DDSsoPublicJwk("OKP", kid, "sig", "Ed25519", "Ed25519", publicKey)
        )));
    }

    @NotNull
    private static Map<String, Object> header(@NotNull String kid) {
        return new HashMap<>(Map.of("alg", "Ed25519", "kid", kid, "typ", "JWT"));
    }

    @NotNull
    private static String encode(@NotNull String value) {
        return encode(value.getBytes(StandardCharsets.UTF_8));
    }

    @NotNull
    private static String encode(@NotNull byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    @NotNull
    private static Map<String, Object> claims() {
        return new HashMap<>(Map.of("iss", "https://sso.example.com", "aud", "account", "sub", "user-id",
            "email", "user@example.com", "source", "datadam-account", "token_use", "identity",
            "iat", NOW.getEpochSecond(), "exp", NOW.plusSeconds(300).getEpochSecond(), "auth_time", NOW.minusSeconds(60).getEpochSecond()));
    }
}
