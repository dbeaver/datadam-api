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
import com.dbeaver.datadam.share.api.model.*;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DDProjectService {

    @NotNull
    List<DDProject> listProjects() throws DDShareException;

    @NotNull
    DDProject createProject(@NotNull String name, @Nullable String description) throws DDShareException;

    @NotNull
    DDProject updateProject(@NotNull UUID projectId, @NotNull String name, @Nullable String description)
        throws DDShareException;

    boolean deleteProject(@NotNull UUID projectId) throws DDShareException;

    @NotNull
    DDProjectConfiguration pullProjectConfiguration(@NotNull UUID projectId) throws DDShareException;

    @NotNull
    DDProjectRevision getCurrentProjectRevision(@NotNull UUID projectId) throws DDShareException;

    /**
     * Pushes encrypted project files if {@code expectedRevisionId} is still current.
     * Returns the new revision; a stale expected revision causes the operation to fail.
     */
    @NotNull
    DDProjectRevision pushProjectConfiguration(
        @NotNull UUID projectId,
        @NotNull UUID expectedRevisionId,
        @NotNull List<DDProjectFile> files
    ) throws DDShareException;

    /**
     * Returns project update history within the specified time range.
     * A {@code null} boundary leaves that side of the range unbounded.
     */
    @NotNull
    List<DDProjectUpdateHistory> getProjectUpdateHistory(
        @NotNull UUID projectId,
        @Nullable OffsetDateTime startTime,
        @Nullable OffsetDateTime endTime
    ) throws DDShareException;
}
