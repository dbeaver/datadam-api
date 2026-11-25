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
package com.dbeaver.datadam.dam.remote.api.service.web;

import com.dbeaver.datadam.dam.remote.api.exception.DDRemoteDamException;
import com.dbeaver.datadam.dam.remote.api.model.DDGatewayKeyMaterial;
import org.jkiss.code.NotNull;

/** Account-scoped key registration on the backend, called by an authenticated Desktop session. */
public interface DDGatewayKeyService {
    String SERVICE_ID = "datadam.account.gateway-keys";

    /** Completes when the key material has been registered*/
    void registerKey(@NotNull DDGatewayKeyMaterial material) throws DDRemoteDamException;
}
