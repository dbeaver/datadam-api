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
package com.dbeaver.datadam.lm.api.service;

import com.dbeaver.datadam.lm.api.model.DDExternalLicenseActivation;
import com.dbeaver.datadam.lm.api.model.DDExternalLicenseActivationResponse;
import org.jkiss.code.NotNull;
import org.jkiss.utils.rest.RequestBody;
import org.jkiss.utils.rest.RequestMapping;
import org.jkiss.utils.rest.RestClient;
import org.jkiss.utils.rest.RpcException;

import java.net.URI;

/**
 * Public API for reporting activations of externally issued licenses.
 */
public interface DDExternalLicenseService {
    /**
     * Creates a client for the public service base URI (including {@code /lmp}).
     * Release its transport resources with {@link RestClient#close(Object)} when it is no longer needed.
     */
    @NotNull
    static DDExternalLicenseService create(@NotNull URI serviceUri) {
        return RestClient.builder(serviceUri, DDExternalLicenseService.class).create();
    }

    /**
     * Records an activation. Duplicate event IDs are accepted without modifying the first record.
     *
     * @return a successful response after recording the activation or accepting a duplicate
     * @throws RpcException if the request fails or the server rejects it; HTTP error bodies use the same response model
     */
    @NotNull
    @RequestMapping(value = "externalLicenseActivation", timeout = 30)
    DDExternalLicenseActivationResponse recordActivation(@RequestBody @NotNull DDExternalLicenseActivation activation)
        throws RpcException;
}
