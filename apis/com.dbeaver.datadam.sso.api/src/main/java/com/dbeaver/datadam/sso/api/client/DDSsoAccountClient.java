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
import com.dbeaver.datadam.sso.api.model.DDSsoAccessContextResponse;
import com.dbeaver.rest.client.AbstractRestClient;
import com.dbeaver.rest.client.MediaType;
import org.jkiss.code.NotNull;
import org.jkiss.dbeaver.DBException;
import org.jkiss.utils.CommonUtils;
import org.jkiss.utils.HttpConstants;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Backend-only client; a desktop never receives the service credential. */
public class DDSsoAccountClient extends AbstractRestClient {
    private static final int MAX_TOKEN_LENGTH = 16_384;
    @NotNull
    private final String serviceSecret;

    public DDSsoAccountClient(@NotNull String backchannelUrl, @NotNull String serviceSecret) {
        super(URI.create(backchannelUrl).resolve("/").toString(), DEFAULT_CONNECT_TIMEOUT, 10_000,
            List.of(), HttpClient.Redirect.NEVER);
        URI base = URI.create(backchannelUrl);
        if (base.getHost() == null || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
            || !("https".equals(base.getScheme()) || "http".equals(base.getScheme()))
            || serviceSecret.length() < 32 || serviceSecret.length() > 512
            || !serviceSecret.chars().allMatch(value -> value >= 33 && value <= 126)) {
            throw new IllegalArgumentException("Account backchannel URL and service credentials are required");
        }
        this.serviceSecret = serviceSecret;
    }

    @NotNull
    public DDSsoAccessContextResponse exchange(@NotNull String accessToken) throws DDSsoClientException {
        if (accessToken.isBlank() || accessToken.length() > MAX_TOKEN_LENGTH) {
            throw new DDSsoClientException("Invalid Account access token");
        }
        try {
            HttpRequest.Builder request = HttpRequest.newBuilder(buildUri(CommonUtils.removeLeadingSlash(DDSsoConstants.ACCESS_CONTEXT_PATH), Map.of()))
                .header(HttpConstants.HEADER_AUTHORIZATION, HttpConstants.BEARER_PREFIX + accessToken)
                .header(DDSsoConstants.HEADER_SERVICE_TOKEN, serviceSecret)
                .header(HttpConstants.HEADER_CACHE_CONTROL, HttpConstants.CACHE_CONTROL_NO_STORE)
                .POST(createBodyPublisher(null, MediaType.JSON));
            DDSsoAccessContextResponse context = execute(request, DDSsoAccessContextResponse.class);
            if (context == null || context.userId() == null || context.accountId() == null
                || context.accountType() == null || context.subscriptionPlanType() == null || context.permissions() == null
                || context.permissions().stream().anyMatch(permission -> permission == null || permission.isBlank())) {
                throw new DDSsoClientException("Invalid Account access context");
            }
            UUID.fromString(context.userId());
            UUID.fromString(context.accountId());
            return context;
        } catch (DBException | IllegalArgumentException e) {
            throw new DDSsoClientException("Account service is unavailable");
        }
    }

    @NotNull
    @Override
    protected DBException mapErrorResponse(int code, @NotNull String message, @NotNull URI uri) {
        return new DBException("Account access context unavailable");
    }
}
