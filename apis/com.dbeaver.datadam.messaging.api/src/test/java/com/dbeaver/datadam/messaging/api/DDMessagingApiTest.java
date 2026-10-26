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
package com.dbeaver.datadam.messaging.api;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DDMessagingApiTest {
    @Test
    void acceptsSubscriberIdsWithSubscriptionMetadataCharactersAndLengthBoundaries() {
        for (String subscriberId : new String[] {"s", "service:account", "service account", "s".repeat(129), "s".repeat(256)}) {
            Assertions.assertTrue(DDMessagingApi.isValidSubscriberId(subscriberId));
        }
    }

    @Test
    void rejectsMissingBlankAndOversizedSubscriberIds() {
        for (String subscriberId : new String[] {null, "", " ", "\t\n", "s".repeat(257)}) {
            Assertions.assertFalse(DDMessagingApi.isValidSubscriberId(subscriberId));
        }
    }

    @Test
    void acceptsPrintableAsciiSecretsAtBothLengthBoundaries() {
        Assertions.assertTrue(DDMessagingApi.isValidServiceSecret("!".repeat(32)));
        Assertions.assertTrue(DDMessagingApi.isValidServiceSecret("~".repeat(512)));
        StringBuilder printable = new StringBuilder();
        for (char value = 33; value <= 126; value++) {
            printable.append(value);
        }
        Assertions.assertTrue(DDMessagingApi.isValidServiceSecret(printable.toString()));
    }

    @Test
    void rejectsMissingShortAndOversizedSecrets() {
        Assertions.assertFalse(DDMessagingApi.isValidServiceSecret(null));
        Assertions.assertFalse(DDMessagingApi.isValidServiceSecret(""));
        Assertions.assertFalse(DDMessagingApi.isValidServiceSecret("x".repeat(31)));
        Assertions.assertFalse(DDMessagingApi.isValidServiceSecret("x".repeat(513)));
    }

    @Test
    void rejectsSpacesControlCharactersAndNonAsciiSecrets() {
        for (String invalid : new String[] {" ", "\t", "\r", "\n", "\0", "\u007f", "\u0080", "é"}) {
            Assertions.assertFalse(DDMessagingApi.isValidServiceSecret("x".repeat(31) + invalid));
        }
    }

    @Test
    void acceptsHttpAndHttpsServiceOrigins() {
        for (String url : new String[] {
            "http://localhost", "http://localhost/", "https://example.com", "HTTPS://example.com/",
            "http://127.0.0.1:1", "https://example.com:65535/", "http://[::1]", "https://[::1]:8443/"
        }) {
            Assertions.assertTrue(DDMessagingApi.isValidServiceUrl(url));
        }
    }

    @Test
    void rejectsMalformedServiceOrigins() {
        for (String url : new String[] {
            null, "", " ", "/relative", "example.com", "//example.com", "http://", "http:///", "http://bad host",
            "ftp://example.com", "ws://example.com", "wss://example.com", "https://user@example.com",
            "https://user:password@example.com", "https://example.com?", "https://example.com?token=value",
            "https://example.com#", "https://example.com#fragment", "https://example.com/path", "https://example.com//",
            "https://example.com/%2f", "https://example.com:0", "https://example.com:65536", "https://example.com:-1",
            "https://example.com:port", "http://[::1", "http://example.com/\n"
        }) {
            Assertions.assertFalse(DDMessagingApi.isValidServiceUrl(url));
        }
    }

    @Test
    void boundsServiceUrlLength() {
        String prefix = "https://";
        Assertions.assertTrue(DDMessagingApi.isValidServiceUrl(prefix + "a".repeat(4096 - prefix.length())));
        Assertions.assertFalse(DDMessagingApi.isValidServiceUrl(prefix + "a".repeat(4097 - prefix.length())));
    }
}
