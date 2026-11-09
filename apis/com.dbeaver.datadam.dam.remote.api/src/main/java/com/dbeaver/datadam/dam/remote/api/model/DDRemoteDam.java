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
package com.dbeaver.datadam.dam.remote.api.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

public record DDRemoteDam(
    @NotNull String damId,
    @NotNull String appDeploymentId,
    @NotNull String appInstanceId,
    @NotNull String workspaceId,
    @NotNull String product,
    @NotNull String version,
    @Nullable String remoteAddress,
    boolean online,
    @Nullable String damSessionId,
    @NotNull Instant firstConnectedAt,
    @NotNull Instant lastConnectedAt,
    @Nullable Instant lastDisconnectedAt,
    @Nullable String clientDamId,
    int sessionCount,
    @NotNull List<Application> applications,
    @NotNull List<String> remoteAddresses,
    @Nullable String damName
) {
    public static final int MAX_DAM_NAME_LENGTH = 256;
    private static final Pattern NAME_EDGE_WHITESPACE = Pattern.compile("\\A[\\s\\p{Z}\\x{FEFF}]+|[\\s\\p{Z}\\x{FEFF}]+\\z");

    public DDRemoteDam {
        DDRemoteDamMessage.requireText(damId, "damId");
        DDRemoteDamMessage.requireText(appDeploymentId, "appDeploymentId");
        DDRemoteDamMessage.requireText(appInstanceId, "appInstanceId");
        DDRemoteDamMessage.requireText(workspaceId, "workspaceId");
        DDRemoteDamMessage.requireText(product, "product");
        DDRemoteDamMessage.requireText(version, "version");
        if (remoteAddress != null) {
            DDRemoteDamMessage.requireText(remoteAddress, "remoteAddress");
        }
        if (online != (damSessionId != null)) {
            throw new IllegalArgumentException("Online status must match DAM session ID presence");
        }
        if (damSessionId != null) {
            DDRemoteDamMessage.requireText(damSessionId, "damSessionId");
        }
        Objects.requireNonNull(firstConnectedAt, "firstConnectedAt");
        Objects.requireNonNull(lastConnectedAt, "lastConnectedAt");
        if (clientDamId != null) {
            DDRemoteDamMessage.requireText(clientDamId, "clientDamId");
        }
        if (sessionCount < 0 || online != (sessionCount > 0)) {
            throw new IllegalArgumentException("Online status must match active session count");
        }
        applications = List.copyOf(applications);
        remoteAddresses = List.copyOf(remoteAddresses);
        remoteAddresses.forEach(address -> DDRemoteDamMessage.requireText(address, "remoteAddress"));
        if (damName != null) {
            damName = normalizeStoredName(damName);
        }
    }

    public DDRemoteDam(
        @NotNull String damId,
        @NotNull String appDeploymentId,
        @NotNull String appInstanceId,
        @NotNull String workspaceId,
        @NotNull String product,
        @NotNull String version,
        @Nullable String remoteAddress,
        boolean online,
        @Nullable String damSessionId,
        @NotNull Instant firstConnectedAt,
        @NotNull Instant lastConnectedAt,
        @Nullable Instant lastDisconnectedAt,
        @Nullable String clientDamId,
        int sessionCount,
        @NotNull List<Application> applications,
        @NotNull List<String> remoteAddresses
    ) {
        this(damId, appDeploymentId, appInstanceId, workspaceId, product, version, remoteAddress, online, damSessionId,
            firstConnectedAt, lastConnectedAt, lastDisconnectedAt, clientDamId, sessionCount, applications, remoteAddresses, null);
    }

    @NotNull
    public static String normalizeName(@NotNull String damName) {
        String name = NAME_EDGE_WHITESPACE.matcher(Objects.requireNonNull(damName, "damName")).replaceAll("");
        if (name.codePoints().allMatch(codePoint ->
            Character.isSpaceChar(codePoint) || Character.getType(codePoint) == Character.FORMAT)) {
            throw new IllegalArgumentException("A Dam name is required");
        }
        if (name.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Dam name must not contain control characters");
        }
        return normalizeStoredName(name);
    }

    @NotNull
    private static String normalizeStoredName(@NotNull String damName) {
        String name = damName.strip();
        if (name.isBlank()) {
            throw new IllegalArgumentException("A Dam name is required");
        }
        if (name.codePointCount(0, name.length()) > MAX_DAM_NAME_LENGTH) {
            throw new IllegalArgumentException("Dam name must not exceed " + MAX_DAM_NAME_LENGTH + " Unicode code points");
        }
        return name;
    }

    public record Application(@NotNull String product, @NotNull String version) {
        public Application {
            DDRemoteDamMessage.requireText(product, "product");
            DDRemoteDamMessage.requireText(version, "version");
        }
    }
}
