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
package com.dbeaver.datadam.share.api.service;

import com.dbeaver.datadam.share.api.exception.DDShareException;
import com.dbeaver.datadam.share.api.model.DDProjectStatistics;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DDProjectStatisticsService {
    @NotNull
    List<DDProjectStatistics> getProjectStatistics(
        @NotNull UUID projectId,
        @Nullable OffsetDateTime startTime,
        @Nullable OffsetDateTime endTime,
        int offset,
        int limit
    ) throws DDShareException;
}
