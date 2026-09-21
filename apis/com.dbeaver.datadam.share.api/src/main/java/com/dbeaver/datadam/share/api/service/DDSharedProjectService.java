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
import com.dbeaver.datadam.share.api.model.DDSharedProjectPullResponse;
import com.dbeaver.datadam.share.api.model.DDSharedProjectRevision;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Contract for storing and syncing DBeaver projects shared between machines/users.
 */
public interface DDSharedProjectService {

    @NotNull
    List<DDSharedProject> listProjects() throws DDShareException;

    @NotNull
    DDSharedProject createProject(
        @NotNull UUID projectId,
        @NotNull String name,
        @Nullable String description
    ) throws DDShareException;

    @NotNull
    DDSharedProject updateProject(
        @NotNull UUID projectId,
        @NotNull String name,
        @Nullable String description
    ) throws DDShareException;

    boolean deleteProject(@NotNull UUID projectId) throws DDShareException;

    @NotNull
    DDSharedProjectPullResponse pullProjectConfiguration(@NotNull UUID projectId) throws DDShareException;

    @NotNull
    DDSharedProjectRevision getCurrentProjectRevision(@NotNull UUID projectId) throws DDShareException;

    /**
     * Pushes project content if {@code lastKnownRevision} matches the current server revision.
     * The comparison and update are performed atomically.
     *
     * @param projectContent content with its client-calculated configuration fingerprint
     * @param lastKnownConfigurationFingerprint last server revision observed by the client
     */
    @NotNull
    DDSharedProjectRevision pushProjectConfiguration(
        @NotNull UUID projectId,
        @NotNull DDSharedProjectConfiguration projectContent,
        @NotNull String lastKnownConfigurationFingerprint
    ) throws DDShareException;

    /**
     * Returns project revisions created within the specified time range.
     * A {@code null} boundary leaves that side of the range unbounded.
     */
    @NotNull
    List<DDSharedProjectRevision> getProjectRevisions(
        @NotNull UUID projectId,
        @Nullable LocalDateTime startTime,
        @Nullable LocalDateTime endTime
    ) throws DDShareException;
}
