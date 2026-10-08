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
import com.google.gson.Gson;
import com.google.gson.JsonParser;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.util.Base64URL;
import com.sun.net.httpserver.HttpServer;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import javax.net.ssl.SSLHandshakeException;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketTimeoutException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.cert.CertPathBuilderException;
import java.security.cert.CertificateExpiredException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

class DDSsoClientTest {
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
    private HttpServer server;
    private KeyPair keyPair;
    private DDSsoClient client;

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
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        client = new DDSsoClient(new DDSsoClientConfig("https://sso.example.com",
            "http://127.0.0.1:" + server.getAddress().getPort(), "account", "test-secret",
            "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5)),
            Clock.fixed(NOW, ZoneOffset.UTC));
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
    void joinsSharedPathsWithoutLosingIssuerPathOrDuplicatingSlashes(String issuer, String expected) {
        var configured = new DDSsoClient(new DDSsoClientConfig(issuer, "", "account", "test-secret",
            "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5)));
        Assertions.assertThat(configured.endpoint(DDSsoConstants.AUTHORIZE_PATH).toString()).isEqualTo(expected);
        Assertions.assertThat(configured.endpoint("authorize").toString()).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/internal", "/internal/"})
    void preservesBackchannelPathWithSharedEndpointPaths(String path) throws Exception {
        var configured = new DDSsoClient(new DDSsoClientConfig("https://sso.example.com/issuer/",
            "http://127.0.0.1:" + server.getAddress().getPort() + path, "account", "test-secret",
            "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5)));
        responseJson.set(gson.toJson(Map.of("handoff", "A".repeat(43), "expires_in", 60)));
        var request = new DDSsoHandoffRequest(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW),
            new DDSsoAuthorizeRequest("account", "https://account.example.com/sso/callback", "state", "A".repeat(43), "S256"), "B".repeat(43));
        Assertions.assertThat(configured.createHandoff(request)).isEqualTo("https://sso.example.com/issuer/handoff?handoff=" + "A".repeat(43));
        Assertions.assertThat(requestPath).hasValue("/internal/handoff");
    }

    @Test
    void validatesTokenAndCachesTrustedJwks() throws Exception {
        String token = signed(claims(), header("key1"));
        var first = client.verifyIdentity(token);
        Assertions.assertThat(first).isEqualTo(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW.minusSeconds(60)));
        Assertions.assertThat(client.verifyIdentity(token)).isEqualTo(first);
        Assertions.assertThat(jwksRequests).hasValue(1);
        Assertions.assertThat(jwksMethod).hasValue("GET");
    }

    @Test
    void acceptsSignedMfaProofAndRejectsInvalidAssuranceClaims() throws Exception {
        Map<String, Object> claims = claims();
        Instant verifiedAt = NOW.minusSeconds(20).plusNanos(123_456_000);
        claims.put(DDSsoConstants.CLAIM_MFA_VERIFIED_AT, verifiedAt.toString());
        Assertions.assertThat(client.verifyIdentity(signed(claims, header("key1"))))
            .isEqualTo(new DDSsoIdentity("user-id", "user@example.com", "datadam-account", NOW.minusSeconds(60), verifiedAt));
        for (Object invalid : List.of("true", true, -1, NOW.plusSeconds(1).toString(),
            NOW.minusSeconds(61).toString())) {
            claims.put(DDSsoConstants.CLAIM_MFA_VERIFIED_AT, invalid);
            Assertions.assertThatThrownBy(() -> client.verifyIdentity(signed(claims, header("key1"))))
                .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
        }
    }

    @Test
    void rejectsMfaProofAddedAfterIdentityTokenWasSigned() throws Exception {
        Map<String, Object> claims = claims();
        String[] token = signed(claims, header("key1")).split("\\.");
        claims.put(DDSsoConstants.CLAIM_MFA_VERIFIED_AT, NOW.minusSeconds(20).toString());
        String modified = token[0] + "." + Base64URL.encode(gson.toJson(claims)) + "." + token[2];

        Assertions.assertThatThrownBy(() -> client.verifyIdentity(modified))
            .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
    }

    @Test
    void exchangesCodeAndVerifierOnlyInAuthenticatedFormBody() throws Exception {
        responseJson.set(gson.toJson(Map.of("id_token", signed(claims(), header("key1")), "expires_in", 300)));
        Assertions.assertThat(client.exchange("code+/%", "verifier")).isNotNull();
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
        Assertions.assertThat(client.createHandoff(request)).isEqualTo("https://sso.example.com/handoff?handoff=" + "A".repeat(43));
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
    void rejectsInvalidIdentityClaimsEvenWithCorrectSignature(String claim, Object value) throws Exception {
        Map<String, Object> claims = claims();
        if (value == null) claims.remove(claim); else claims.put(claim, value);
        String token = signed(claims, header("key1"));
        Assertions.assertThatThrownBy(() -> client.verifyIdentity(token)).isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
    }

    static Stream<Arguments> invalidClaims() {
        return Stream.of(Arguments.of("iss", "https://other.example"), Arguments.of("aud", "admin"),
            Arguments.of("aud", List.of("account", "admin")), Arguments.of("token_use", "sso_session"),
            Arguments.of("source", "another-source"), Arguments.of("sub", ""), Arguments.of("email", null),
            Arguments.of("exp", NOW.getEpochSecond()), Arguments.of("exp", null), Arguments.of("exp", "9999999999"),
            Arguments.of("iat", NOW.plusSeconds(31).getEpochSecond()), Arguments.of("auth_time", null),
            Arguments.of("auth_time", NOW.plusSeconds(1).getEpochSecond()), Arguments.of("nbf", NOW.plusSeconds(1).getEpochSecond()));
    }

    @Test
    void rejectsChangedSignatureAndUnsupportedHeaders() throws Exception {
        String original = signed(claims(), header("key1"));
        String tampered = original.substring(0, original.lastIndexOf('.') + 1) + Base64URL.encode(new byte[64]);
        Assertions.assertThatThrownBy(() -> client.verifyIdentity(tampered)).isInstanceOf(DDSsoClientException.class);
        for (JWSHeader header : List.of(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("key1").type(JOSEObjectType.JWT).build(),
            new JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID("key1").build())) {
            Assertions.assertThatThrownBy(() -> client.verifyIdentity(signed(claims(), header))).isInstanceOf(DDSsoClientException.class);
        }
    }

    @Test
    void rejectsPrivateKeyFieldsBeforeMappingToPublicKeyModel() throws Exception {
        var response = JsonParser.parseString(keysJson.get()).getAsJsonObject();
        response.getAsJsonArray("keys").get(0).getAsJsonObject().addProperty("d", Base64URL.encode(new byte[32]).toString());
        keysJson.set(gson.toJson(response));
        Assertions.assertThatThrownBy(() -> client.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class).hasMessage("Invalid SSO identity token");
    }

    @Test
    void rejectsDuplicatePublicKeyIds() throws Exception {
        var response = gson.fromJson(keysJson.get(), DDSsoJwksResponse.class);
        keysJson.set(gson.toJson(new DDSsoJwksResponse(List.of(response.keys().getFirst(), response.keys().getFirst()))));
        Assertions.assertThatThrownBy(() -> client.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    @ParameterizedTest
    @MethodSource("invalidPublicKeys")
    void rejectsInvalidPublicKeyParameters(String field, Object value) throws Exception {
        var response = JsonParser.parseString(keysJson.get()).getAsJsonObject();
        response.getAsJsonArray("keys").get(0).getAsJsonObject().add(field, gson.toJsonTree(value));
        keysJson.set(gson.toJson(response));
        Assertions.assertThatThrownBy(() -> client.verifyIdentity(signed(claims(), header("key1"))))
            .isInstanceOf(DDSsoClientException.class);
    }

    static Stream<Arguments> invalidPublicKeys() {
        return Stream.of(
            Arguments.of("kty", "RSA"), Arguments.of("crv", "X25519"), Arguments.of("alg", "EdDSA"),
            Arguments.of("use", "enc"), Arguments.of("kid", ""), Arguments.of("kid", 123),
            Arguments.of("x", null), Arguments.of("x", Base64URL.encode(new byte[31]).toString())
        );
    }

    @Test
    void refreshesUnknownKidFromConfiguredJwksOnly() throws Exception {
        client.verifyIdentity(signed(claims(), header("key1")));
        keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        keysJson.set(jwks("key2", keyPair));
        Assertions.assertThat(client.verifyIdentity(signed(claims(), header("key2")))).isNotNull();
        Assertions.assertThat(jwksRequests).hasValue(2);
        Assertions.assertThatThrownBy(() -> client.verifyIdentity(signed(claims(), header("missing")))).isInstanceOf(DDSsoClientException.class);
    }

    @Test
    void reportsHandshakeCauseChainWithoutExceptionData() {
        var handshake = new SSLHandshakeException("PKIX path building failed: https://user:secret@example.com/?token=private");
        var path = new CertPathBuilderException("unable to find valid certification path to requested target: private certificate");
        handshake.initCause(path);
        path.initCause(new CertificateExpiredException("private certificate details\nforged log entry"));

        Assertions.assertThat(DDSsoClient.describeFailure(handshake)).isEqualTo(
            "SSLHandshakeException[PKIX path building failed] -> "
                + "CertPathBuilderException[unable to find valid certification path] -> CertificateExpiredException"
        );
    }

    @ParameterizedTest
    @CsvSource({
        "Remote host terminated the handshake, Remote host terminated the handshake",
        "No appropriate protocol (protocol is disabled or cipher suites are inappropriate), No appropriate protocol",
        "No subject alternative DNS name matching private.example found., No subject alternative DNS name matching",
        "Received fatal alert: protocol_version, Received fatal alert: protocol_version",
        "Received fatal alert: handshake_failure, Received fatal alert: handshake_failure",
        "Received fatal alert: certificate_required, Received fatal alert: certificate_required",
        "Received fatal alert: private-data, Received fatal alert"
    })
    void reportsOnlyRecognizedHandshakeDetails(String message, String expected) {
        Assertions.assertThat(DDSsoClient.describeFailure(new SSLHandshakeException(message)))
            .isEqualTo("SSLHandshakeException[" + expected + "]");
    }

    @Test
    void distinguishesPeerClosureAndReadTimeoutWithoutLoggingArbitraryMessages() {
        var handshake = new SSLHandshakeException("Remote host terminated the handshake");
        handshake.initCause(new EOFException("SSL peer shut down incorrectly"));
        Assertions.assertThat(DDSsoClient.describeFailure(handshake)).isEqualTo(
            "SSLHandshakeException[Remote host terminated the handshake] -> EOFException[SSL peer shut down incorrectly]"
        );
        Assertions.assertThat(DDSsoClient.describeFailure(new SocketTimeoutException("Read timed out")))
            .isEqualTo("SocketTimeoutException[Read timed out]");
        Assertions.assertThat(DDSsoClient.describeFailure(new IOException("Authorization: Basic private\nprivate response body")))
            .isEqualTo("IOException");
    }

    @Test
    void boundsCyclicCauseChainsAndAcceptsMissingMessages() {
        var first = new IOException();
        var second = new EOFException();
        first.initCause(second);
        second.initCause(first);
        Assertions.assertThat(DDSsoClient.describeFailure(first))
            .isEqualTo("IOException -> EOFException -> IOException -> EOFException -> IOException -> EOFException -> IOException -> EOFException -> ...");
    }

    @Test
    void preservesSafeClientErrorWhenTlsPeerClosesDuringHandshake() throws Exception {
        try (var listener = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress());
             var executor = Executors.newSingleThreadExecutor()) {
            listener.setSoTimeout(5000);
            var peer = executor.submit(() -> {
                try (var socket = listener.accept()) {
                    socket.setSoTimeout(5000);
                    // Read a TLS record header, then close without completing the handshake.
                    Assertions.assertThat(socket.getInputStream().readNBytes(5)).hasSize(5);
                }
                return null;
            });
            var configured = new DDSsoClient(new DDSsoClientConfig("https://sso.example.com",
                "https://localhost:" + listener.getLocalPort(), "account", "test-secret",
                "https://account.example.com/sso/callback", "datadam-account", Duration.ofMinutes(5)));

            Assertions.assertThatThrownBy(() -> configured.exchange("private-code", "private-verifier"))
                .isInstanceOf(DDSsoClientException.class).hasMessage("SSO service is unavailable").hasNoCause();
            peer.get(10, TimeUnit.SECONDS);
        }
    }

    private String signed(Map<String, Object> claims, JWSHeader header) throws Exception {
        String input = header.toBase64URL() + "." + Base64URL.encode(gson.toJson(claims));
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(keyPair.getPrivate());
        signer.update(input.getBytes(StandardCharsets.US_ASCII));
        return input + "." + Base64URL.encode(signer.sign());
    }

    private String jwks(String kid, KeyPair pair) {
        String publicKey = Base64URL.encode(
            SubjectPublicKeyInfo.getInstance(pair.getPublic().getEncoded()).getPublicKeyData().getBytes()
        ).toString();
        return gson.toJson(new DDSsoJwksResponse(List.of(
            new DDSsoPublicJwk("OKP", kid, "sig", "Ed25519", "Ed25519", publicKey)
        )));
    }

    private static JWSHeader header(String kid) {
        return new JWSHeader.Builder(JWSAlgorithm.Ed25519).keyID(kid).type(JOSEObjectType.JWT).build();
    }

    private static Map<String, Object> claims() {
        return new HashMap<>(Map.of("iss", "https://sso.example.com", "aud", "account", "sub", "user-id",
            "email", "user@example.com", "source", "datadam-account", "token_use", "identity",
            "iat", NOW.getEpochSecond(), "exp", NOW.plusSeconds(300).getEpochSecond(), "auth_time", NOW.minusSeconds(60).getEpochSecond()));
    }
}
