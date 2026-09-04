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

import com.dbeaver.datadam.dm.api.model.DMDomainAction;
import com.dbeaver.datadam.dm.api.model.DMDomainHistoryInfo;
import com.dbeaver.datadam.dm.api.model.DMDomainInfo;
import com.dbeaver.datadam.dm.api.model.DMException;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;

import java.util.List;

/**
 * Domain service.
 * CRUD for deployment's domain info.
 */
public interface DMDomainService {

    /**
     * Adds domain to deployment.
     * @param domainName domain name of deployment.
     */
    boolean addDomain(@NotNull String domainName) throws DMException;

    /**
     * Updates domain info from the current deployment.
     * @param domainName new domain name of the deployment.
     */
    void updateDomain(@NotNull String domainName) throws DMException;

    /**
     * Updates date of expire for domain from the current deployment.
     */
    void refreshDomain() throws DMException;

    /**
     * Deletes domain info from the current deployment.
     */
    void deleteDomain(@NotNull String domainName) throws DMException;

    /**
     * Adds record to domain management history.
     */
    void addDomainAction(@NotNull String domainName, @NotNull DMDomainAction action, @Nullable String message) throws DMException;

    /**
     * Finds domain info from the current deployment.
     */
    DMDomainInfo findDomain();

    /**
     * Returns domain info from the specified deployment.
     */
    DMDomainInfo getDomainInfo(long deploymentId);

    /**
     * Find all domains related to the current deployment.
     */
    List<DMDomainInfo> findDomains();

    /**
     * Adds domain records to DO for generating certificate.
     */
    void addDomainRecords(@NotNull String domainName, @Nullable String dnsKey) throws DMException;

    /**
     * Returns record with error message with something is wrong with certificate renewal for the deployment.
     */
    DMDomainHistoryInfo getDomainRefreshErrorRecord() throws DMException;

    /**
     * Deletes domain records from DO.
     */
    void deleteDomainRecords(@NotNull String domainName) throws DMException;

    /**
     * Updates domain records in DO for renewing certificates.
     */
    void updateDomainRecords(@NotNull String dnsKey) throws DMException;

    /**
     * Checks if limit of domain generation has been exceeded.
     */
    void validateDomainGenerationFrequency() throws DMException;
}
