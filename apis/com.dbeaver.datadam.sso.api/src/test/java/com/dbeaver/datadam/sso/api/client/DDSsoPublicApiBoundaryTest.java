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
package com.dbeaver.datadam.sso.api.client;

import org.jkiss.code.NotNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DDSsoPublicApiBoundaryTest {
    @ParameterizedTest
    @ValueSource(strings = {
        "DDSsoAccountApi", "DDSsoLoginApi", "client.DDSsoAccountClient", "model.DDSsoAccessContextResponse",
        "model.DDSsoLoginApprovalRequest", "model.DDSsoLoginApprovalResponse"
    })
    void doesNotShipBackendOnlyContracts(@NotNull String type) {
        assertThatThrownBy(() -> Class.forName("com.dbeaver.datadam.sso.api." + type)).isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void doesNotExposeLoginApprovalOperations() {
        assertThat(DDSsoClient.class.getMethods()).noneMatch(method -> method.getName().equals("approveLogin"));
        assertThat(DDSsoService.class.getMethods()).noneMatch(method -> method.getName().equals("approveLogin"));
    }
}
