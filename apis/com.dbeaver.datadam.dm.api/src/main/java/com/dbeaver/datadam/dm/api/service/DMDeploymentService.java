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
package com.dbeaver.datadam.dm.api.service;

import com.dbeaver.datadam.dm.api.model.DMDeploymentInfo;
import com.dbeaver.datadam.dm.api.model.DMException;
import org.jkiss.code.NotNull;

import java.util.List;

/**
 * Deployment service.
 */
public interface DMDeploymentService {

    /**
     * Checks whether the provided deployment's IP address is not accessible from the external network.
     */
    boolean validateDeployment(@NotNull String ipAddress);

    /**
     * Updates the current deployment information, specifically the IP address.
     * @param ipAddress the new IP address of deployment.
     */
    void updateDeployment(@NotNull String ipAddress) throws DMException;

    /**
     * Finds all deployments within the organization.
     */
    List<DMDeploymentInfo> findDeployments();

    /**
     * Displays information about the current deployment.
     */
    DMDeploymentInfo getDeployment();

    /**
     * Deletes deployment from organization.
     * @param deploymentId id of deployment.
     */
    void deleteDeployment(long deploymentId) throws DMException;
}
