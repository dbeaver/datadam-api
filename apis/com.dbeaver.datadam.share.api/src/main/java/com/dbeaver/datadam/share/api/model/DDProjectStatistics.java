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
package com.dbeaver.datadam.share.api.model;

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.OffsetDateTime;
import java.util.UUID;

public record DDProjectStatistics(
    @NotNull UUID id,
    @NotNull UUID userId,
    @NotNull OffsetDateTime time,
    @NotNull Operation type,
    @Nullable String ipAddress,
    @Nullable String userAgent,
    @NotNull String configurationFingerprint
) {
    public enum Operation {
        ProjectPull,
        ProjectPush
    }
}
