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
package com.dbeaver.datadam.sso.api;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;

class DDSsoScopesTest {
    @Test
    void scopesAreCaseSensitiveAndHaveAStableRepresentation() {
        var scopes = DDSsoScopes.parse("dbeaver-storage:access account:access account:access");
        Assertions.assertThat(scopes).isEqualTo(Set.of("dbeaver-storage:access", "account:access"));
        Assertions.assertThat(DDSsoScopes.format(scopes)).isEqualTo("account:access dbeaver-storage:access");
        Assertions.assertThat(DDSsoScopes.parse("Account:access")).doesNotContain("account:access");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", " account:access", "account:access ", "account:access  dbeaver-storage:access",
        "account:access\tdbeaver-storage:access", "account:access\n", "a\"b", "a\\b", "права"})
    void rejectsInvalidWireScopes(String scope) {
        Assertions.assertThatThrownBy(() -> DDSsoScopes.parse(scope)).isInstanceOf(IllegalArgumentException.class);
    }
}
