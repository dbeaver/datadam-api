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

import java.time.OffsetDateTime;
import java.util.List;

public record DDConnectedRemoteDam(
    @NotNull String damId,
    @NotNull String appDeploymentId,
    @NotNull String appInstanceId,
    @NotNull String workspaceId,
    @Nullable String remoteAddress,
    boolean online,
    int sessionCount,
    @NotNull List<Application> applications,
    @NotNull OffsetDateTime firstConnectedAt,
    @NotNull OffsetDateTime lastConnectedAt,
    @Nullable OffsetDateTime lastDisconnectedAt,
    @Nullable String clientDamId,
    @NotNull List<String> remoteAddresses,
    @Nullable String damName
) {
    public DDConnectedRemoteDam {
        applications = List.copyOf(applications);
        remoteAddresses = List.copyOf(remoteAddresses);
    }

    public record Application(@NotNull String product, @NotNull String version) {
    }
}
