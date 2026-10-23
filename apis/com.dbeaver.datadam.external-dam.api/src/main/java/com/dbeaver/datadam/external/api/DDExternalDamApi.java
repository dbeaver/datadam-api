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
package com.dbeaver.datadam.external.api;

import org.jkiss.code.Nullable;

import java.net.URI;

public final class DDExternalDamApi {
    public static final String GRAPHQL_PATH = "/internal/graphql";
    public static final String CALLBACK_GRAPHQL_PATH = "/internal/external-dam/graphql";
    public static final String HEADER_SERVICE_TOKEN = "X-DD-Service-Token";
    public static final int MIN_SERVICE_SECRET_LENGTH = 32;
    public static final int MAX_SERVICE_SECRET_LENGTH = 512;
    public static final int MIN_SUBSCRIPTION_LEASE_SECONDS = 15;
    public static final int MAX_SUBSCRIPTION_LEASE_SECONDS = 300;
    private static final int MAX_SUBSCRIBER_ID_LENGTH = 256;
    private static final int MAX_SERVICE_URL_LENGTH = 4096;

    private DDExternalDamApi() {
    }

    public static boolean isValidSubscriberId(@Nullable String subscriberId) {
        return subscriberId != null && !subscriberId.isBlank() && subscriberId.length() <= MAX_SUBSCRIBER_ID_LENGTH;
    }

    public static boolean isValidServiceSecret(@Nullable String secret) {
        return secret != null && secret.length() >= MIN_SERVICE_SECRET_LENGTH && secret.length() <= MAX_SERVICE_SECRET_LENGTH
            && secret.chars().allMatch(value -> value >= 33 && value <= 126);
    }

    public static boolean isValidServiceUrl(@Nullable String serviceUrl) {
        if (serviceUrl == null || serviceUrl.length() > MAX_SERVICE_URL_LENGTH) {
            return false;
        }
        try {
            URI uri = URI.create(serviceUrl);
            return uri.getHost() != null && uri.getUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                && ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                && (uri.getPort() == -1 || uri.getPort() >= 1 && uri.getPort() <= 65535);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
