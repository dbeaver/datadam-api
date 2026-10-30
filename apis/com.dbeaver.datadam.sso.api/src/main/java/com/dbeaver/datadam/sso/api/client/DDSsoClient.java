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
import com.dbeaver.datadam.sso.api.model.DDSsoLoginApprovalRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoLoginApprovalResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoRefreshRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenResponse;
import com.dbeaver.rest.client.AbstractRestClient;
import com.dbeaver.rest.client.MediaType;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.GsonUtils;
import org.jkiss.utils.HttpConstants;
import org.jkiss.utils.oauth.OAuthConstants;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** HTTP transport for SSO endpoints. Returned tokens and keys are unverified. */
public class DDSsoClient extends AbstractRestClient {
    private static final int READ_TIMEOUT_MILLIS = 10_000;
    private final DDSsoClientConfig properties;

    public DDSsoClient(@NotNull DDSsoClientConfig properties) {
        super(CommonUtils.removeTrailingSlash(properties.backchannelUrl() != null && !properties.backchannelUrl().isBlank()
                ? properties.backchannelUrl() : properties.issuer()),
            DEFAULT_CONNECT_TIMEOUT, READ_TIMEOUT_MILLIS, List.of(), HttpClient.Redirect.NEVER);
        this.properties = properties;
        gson = GsonUtils.gsonBuilder().registerTypeAdapter(Instant.class, new GsonUtils.InstantIsoAdapter()).create();
    }

    @NotNull
    public URI endpoint(@NotNull String path) {
        return endpoint(properties.issuer(), path);
    }

    @NotNull
    private static URI endpoint(@NotNull String base, @NotNull String path) {
        return URI.create(base + (base.endsWith("/") ? "" : "/") + (path.startsWith("/") ? path.substring(1) : path));
    }

    /** Exchanges an authorization code for the token response. JWTs are unverified and id_token is optional. */
    @NotNull
    public DDSsoTokenResponse exchangeCodeForTokens(@NotNull DDSsoTokenRequest request) throws DDSsoClientException {
        Map<String, String> parameters = new HashMap<>(Map.of(
            OAuthConstants.PARAM_GRANT_TYPE, OAuthConstants.GRANT_TYPE_AUTH_CODE,
            OAuthConstants.PARAM_CODE, request.code(), OAuthConstants.PARAM_CODE_VERIFIER, request.codeVerifier(),
            OAuthConstants.AUTH_PROP_CLIENT_ID, request.clientId()));
        if (request.redirectUri() != null) {
            parameters.put(OAuthConstants.PARAM_REDIRECT_URI, request.redirectUri());
        }
        if (request.resource() != null) {
            parameters.put(DDSsoConstants.PARAM_RESOURCE, request.resource());
        }
        return postResponse(DDSsoConstants.TOKEN_PATH, parameters, MediaType.FORM_URLENCODED,
            DDSsoTokenResponse.class, "Invalid SSO token response");
    }

    @NotNull
    public DDSsoTokenResponse refresh(@NotNull DDSsoRefreshRequest request) throws DDSsoClientException {
        Map<String, String> parameters = new HashMap<>(Map.of(
            OAuthConstants.PARAM_GRANT_TYPE, OAuthConstants.GRANT_TYPE_REFRESH_TOKEN,
            OAuthConstants.RESPONSE_PARAM_REFRESH_TOKEN, request.refreshToken(), OAuthConstants.AUTH_PROP_CLIENT_ID, request.clientId()));
        if (request.resource() != null) {
            parameters.put(DDSsoConstants.PARAM_RESOURCE, request.resource());
        }
        if (request.scope() != null) {
            parameters.put(DDSsoConstants.PARAM_SCOPE, request.scope());
        }
        return postResponse(DDSsoConstants.TOKEN_PATH, parameters, MediaType.FORM_URLENCODED,
            DDSsoTokenResponse.class, "Invalid SSO token response");
    }

    @NotNull
    public DDSsoLoginApprovalResponse approveLogin(@NotNull DDSsoLoginApprovalRequest request) throws DDSsoClientException {
        return postResponse(DDSsoConstants.LOGIN_APPROVE_PATH, request, MediaType.JSON,
            DDSsoLoginApprovalResponse.class, "Invalid SSO login approval response");
    }

    @NotNull
    public String getJwks() throws DDSsoClientException {
        try {
            byte[] bytes = executeGetRequest(CommonUtils.removeLeadingSlash(DDSsoConstants.JWKS_PATH), byte[].class);
            return DDSsoJson.utf8(bytes);
        } catch (DBException | IOException e) {
            throw new DDSsoClientException("SSO request failed");
        }
    }

    @NotNull
    private <T> T postResponse(@NotNull String path, @NotNull Object body, @NotNull MediaType mediaType,
        @NotNull Class<T> type, @NotNull String message) throws DDSsoClientException {
        try {
            T response = executePostRequest(CommonUtils.removeLeadingSlash(path), Map.of(), body, mediaType, type);
            if (response == null) {
                throw new DDSsoClientException(message);
            }
            return response;
        } catch (DBException e) {
            throw new DDSsoClientException("SSO request failed");
        }
    }

    @NotNull
    @Override
    protected <T> T execute(@NotNull HttpRequest.Builder builder, @NotNull Type type) throws DBException {
        if (HttpConstants.METHOD_POST.equals(builder.build().method())) {
            // RFC 6749 section 2.3.1: form-encode each component before joining the Basic credentials.
            String credentials = URLEncoder.encode(properties.clientId(), StandardCharsets.UTF_8) + ":"
                + URLEncoder.encode(properties.clientSecret(), StandardCharsets.UTF_8);
            builder.setHeader(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.BASIC_PREFIX
                + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
        }
        return super.execute(builder, type);
    }

    @NotNull
    @Override
    protected DBException mapErrorResponse(int code, @NotNull String message, @NotNull URI uri) {
        // Authorization responses must not be included in exceptions or logs.
        return new DBException("SSO request failed");
    }
}
