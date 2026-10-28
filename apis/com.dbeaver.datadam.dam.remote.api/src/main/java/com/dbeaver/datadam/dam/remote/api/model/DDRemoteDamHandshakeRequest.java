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

public record DDRemoteDamHandshakeRequest(
    @NotNull String appDeploymentId,
    @NotNull String appInstanceId,
    @NotNull String workspaceId,
    @NotNull String product,
    @NotNull String version
) {
    public static final String LEGACY_WORKSPACE_ID = "__legacy_workspace__";
    public static final int MAX_WORKSPACE_ID_LENGTH = 256;

    public DDRemoteDamHandshakeRequest {
        DDRemoteDamMessage.requireText(appDeploymentId, "appDeploymentId");
        DDRemoteDamMessage.requireText(appInstanceId, "appInstanceId");
        DDRemoteDamMessage.requireText(workspaceId, "workspaceId");
        if (workspaceId.length() > MAX_WORKSPACE_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid workspaceId");
        }
        DDRemoteDamMessage.requireText(product, "product");
        DDRemoteDamMessage.requireText(version, "version");
    }

    public DDRemoteDamHandshakeRequest(
        @NotNull String appDeploymentId,
        @NotNull String appInstanceId,
        @NotNull String product,
        @NotNull String version
    ) {
        this(appDeploymentId, appInstanceId, LEGACY_WORKSPACE_ID, product, version);
    }
}
