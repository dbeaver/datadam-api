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
import com.dbeaver.datadam.sso.api.model.DDSsoDesktopApprovalRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoDesktopApprovalResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoHandoffResponse;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenRequest;
import com.dbeaver.datadam.sso.api.model.DDSsoTokenResponse;
import com.dbeaver.rest.client.AbstractRestClient;
import com.dbeaver.rest.client.MediaType;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.GsonUtils;
import org.jkiss.utils.HttpConstants;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

    @NotNull
    public DDSsoTokenResponse exchange(@NotNull DDSsoTokenRequest request) throws DDSsoClientException {
        return postResponse(DDSsoConstants.TOKEN_PATH, request, MediaType.FORM_URLENCODED,
            DDSsoTokenResponse.class, "Invalid SSO token response");
    }

    @NotNull
    public DDSsoHandoffResponse createHandoff(@NotNull DDSsoHandoffRequest request) throws DDSsoClientException {
        return postResponse(DDSsoConstants.HANDOFF_PATH, request, MediaType.JSON,
            DDSsoHandoffResponse.class, "Invalid SSO handoff response");
    }

    @NotNull
    public DDSsoDesktopApprovalResponse approveDesktop(@NotNull DDSsoDesktopApprovalRequest request) throws DDSsoClientException {
        return postResponse(DDSsoConstants.DESKTOP_APPROVE_PATH, request, MediaType.JSON,
            DDSsoDesktopApprovalResponse.class, "Invalid SSO desktop approval response");
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
            builder.setHeader(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.BASIC_PREFIX
                + Base64.getEncoder().encodeToString((properties.clientId() + ":" + properties.clientSecret()).getBytes(StandardCharsets.UTF_8)));
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
