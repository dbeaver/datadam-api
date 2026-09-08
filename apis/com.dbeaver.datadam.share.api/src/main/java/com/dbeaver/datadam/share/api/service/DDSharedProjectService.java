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
import com.dbeaver.datadam.share.api.model.DDSharedProject;
import com.dbeaver.datadam.share.api.model.DDSharedProjectConfiguration;
import com.dbeaver.datadam.share.api.model.DDSharedProjectRevision;
import com.dbeaver.datadam.share.api.model.DDSharedProjectUpdateHistory;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public interface DDSharedProjectService {

    @NotNull
    List<DDSharedProject> listProjects() throws DDShareException;

    @NotNull
    DDSharedProject createProject(@NotNull String name, @Nullable String description) throws DDShareException;

    @NotNull
    DDSharedProject updateProject(@NotNull UUID projectId, @NotNull String name, @Nullable String description)
        throws DDShareException;

    boolean deleteProject(@NotNull UUID projectId) throws DDShareException;

    @NotNull
    DDSharedProjectConfiguration pullProjectConfiguration(@NotNull UUID projectId) throws DDShareException;

    @NotNull
    DDSharedProjectRevision getCurrentProjectRevision(@NotNull UUID projectId) throws DDShareException;

    /**
     * Pushes project content using {@code lastKnownRevision} only as an optimistic lock.
     * A stale revision causes the operation to fail; the returned revision identifies the current server content.
     *
     * @param projectContent content with its client-calculated revision
     * @param lastKnownRevision last server revision observed by the client
     */
    @NotNull
    DDSharedProjectRevision pushProjectConfiguration(
        @NotNull UUID projectId,
        @NotNull DDSharedProjectConfiguration projectContent,
        @NotNull DDSharedProjectRevision lastKnownRevision
    ) throws DDShareException;

    /**
     * Returns project update history within the specified time range.
     * A {@code null} boundary leaves that side of the range unbounded.
     */
    @NotNull
    List<DDSharedProjectUpdateHistory> getProjectUpdateHistory(
        @NotNull UUID projectId,
        @Nullable OffsetDateTime startTime,
        @Nullable OffsetDateTime endTime
    ) throws DDShareException;
}
