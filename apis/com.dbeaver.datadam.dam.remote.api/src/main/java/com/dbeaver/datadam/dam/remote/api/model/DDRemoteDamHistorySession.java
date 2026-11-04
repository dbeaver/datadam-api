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

public record DDRemoteDamHistorySession(
    @NotNull String damSessionId,
    @Nullable String appDeploymentId,
    @Nullable String appInstanceId,
    @NotNull String product,
    @NotNull String version,
    @Nullable String remoteAddress,
    boolean online,
    @NotNull Instant connectedAt,
    @Nullable Instant endedAt,
    long durationSeconds
) {
    public DDRemoteDamHistorySession {
        DDRemoteDamMessage.requireText(damSessionId, "damSessionId");
        if (appDeploymentId != null) {
            DDRemoteDamMessage.requireText(appDeploymentId, "appDeploymentId");
        }
        if (appInstanceId != null) {
            DDRemoteDamMessage.requireText(appInstanceId, "appInstanceId");
        }
        DDRemoteDamMessage.requireText(product, "product");
        DDRemoteDamMessage.requireText(version, "version");
        if (remoteAddress != null) {
            DDRemoteDamMessage.requireText(remoteAddress, "remoteAddress");
        }
        Objects.requireNonNull(connectedAt, "connectedAt");
        if (online != (endedAt == null)) {
            throw new IllegalArgumentException("Online status must match session end presence");
        }
        if (durationSeconds < 0) {
            throw new IllegalArgumentException("Session duration must be nonnegative");
        }
    }
}
