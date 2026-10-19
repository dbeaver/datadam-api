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
package com.dbeaver.datadam.external.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDExternalDamApiTest {
    @Test
    void acceptsPrintableAsciiSecretsAtBothLengthBoundaries() {
        Assertions.assertTrue(DDExternalDamApi.isValidServiceSecret("!".repeat(32)));
        Assertions.assertTrue(DDExternalDamApi.isValidServiceSecret("~".repeat(512)));
        StringBuilder printable = new StringBuilder();
        for (char value = 33; value <= 126; value++) {
            printable.append(value);
        }
        Assertions.assertTrue(DDExternalDamApi.isValidServiceSecret(printable.toString()));
    }

    @Test
    void rejectsMissingShortAndOversizedSecrets() {
        Assertions.assertFalse(DDExternalDamApi.isValidServiceSecret(null));
        Assertions.assertFalse(DDExternalDamApi.isValidServiceSecret(""));
        Assertions.assertFalse(DDExternalDamApi.isValidServiceSecret("x".repeat(31)));
        Assertions.assertFalse(DDExternalDamApi.isValidServiceSecret("x".repeat(513)));
    }

    @Test
    void rejectsSpacesControlCharactersAndNonAsciiSecrets() {
        for (String invalid : new String[] {" ", "\t", "\r", "\n", "\0", "\u007f", "\u0080", "é"}) {
            Assertions.assertFalse(DDExternalDamApi.isValidServiceSecret("x".repeat(31) + invalid));
        }
    }
}
