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
import java.util.Objects;

public record DDRemoteDam(
    @NotNull String damId,
    @NotNull String appDeploymentId,
    @NotNull String appInstanceId,
    @NotNull String workspaceId,
    @NotNull String product,
    @NotNull String version,
    boolean online,
    @Nullable String damSessionId,
    @NotNull Instant firstConnectedAt,
    @NotNull Instant lastConnectedAt,
    @Nullable Instant lastDisconnectedAt
) {
    public DDRemoteDam {
        DDRemoteDamMessage.requireText(damId, "damId");
        DDRemoteDamMessage.requireText(appDeploymentId, "appDeploymentId");
        DDRemoteDamMessage.requireText(appInstanceId, "appInstanceId");
        DDRemoteDamMessage.requireText(workspaceId, "workspaceId");
        DDRemoteDamMessage.requireText(product, "product");
        DDRemoteDamMessage.requireText(version, "version");
        if (damSessionId != null) {
            DDRemoteDamMessage.requireText(damSessionId, "damSessionId");
        }
        Objects.requireNonNull(firstConnectedAt, "firstConnectedAt");
        Objects.requireNonNull(lastConnectedAt, "lastConnectedAt");
    }
}
