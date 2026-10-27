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
package com.dbeaver.messaging.requestreply;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Timeout(10)
class MessagingRequestReplyMessageTest {
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();

    @Test
    void preservesTheOpaquePayloadAndCorrelationWhenCreatingReplies() {
        Object payload = new Object();
        MessagingRequestReplyMessage<Object> request = MessagingRequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, payload);

        assertEquals(REQUEST_ID, request.requestId());
        assertEquals(ENDPOINT_ID, request.replyTo());
        assertEquals(MessagingRequestReplyMessage.Type.REQUEST, request.type());
        assertSame(payload, request.payload());
        assertNull(request.error());

        Object result = new Object();
        MessagingRequestReplyMessage<Object> response = request.response(result);

        assertEquals(REQUEST_ID, response.requestId());
        assertEquals(ENDPOINT_ID, response.replyTo());
        assertEquals(MessagingRequestReplyMessage.Type.RESPONSE, response.type());
        assertSame(result, response.payload());
        assertNull(response.error());
        assertSame(payload, request.payload());
    }

    @Test
    void permitsANullResponseWithoutTurningItIntoAnError() {
        MessagingRequestReplyMessage<String> response =
            MessagingRequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, "request").response(null);

        assertEquals(REQUEST_ID, response.requestId());
        assertEquals(ENDPOINT_ID, response.replyTo());
        assertEquals(MessagingRequestReplyMessage.Type.RESPONSE, response.type());
        assertNull(response.payload());
        assertNull(response.error());
    }

    @Test
    void exposesOnlyTheErrorCodeAndOriginalCorrelationInFailures() {
        var request = MessagingRequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, "private request");
        for (MessagingRequestReplyMessage.Error error : MessagingRequestReplyMessage.Error.values()) {
            MessagingRequestReplyMessage<String> failure = request.failure(error);

            assertEquals(REQUEST_ID, failure.requestId());
            assertEquals(ENDPOINT_ID, failure.replyTo());
            assertEquals(MessagingRequestReplyMessage.Type.ERROR, failure.type());
            assertEquals(error, failure.error());
            assertNull(failure.payload());
        }
    }

    @Test
    void rejectsMissingCorrelationAndType() {
        assertThrows(NullPointerException.class,
            () -> new MessagingRequestReplyMessage<>(null, ENDPOINT_ID, MessagingRequestReplyMessage.Type.RESPONSE, null, null));
        assertThrows(NullPointerException.class,
            () -> new MessagingRequestReplyMessage<>(REQUEST_ID, null, MessagingRequestReplyMessage.Type.RESPONSE, null, null));
        assertThrows(NullPointerException.class,
            () -> new MessagingRequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, null, null, null));
        assertThrows(NullPointerException.class, () -> MessagingRequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, null));
    }

    @Test
    void rejectsMalformedPayloadAndErrorCombinations() {
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.REQUEST, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new MessagingRequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.ERROR, null, null));
        for (MessagingRequestReplyMessage.Error error : MessagingRequestReplyMessage.Error.values()) {
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRequestReplyMessage<>(
                    REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.REQUEST, "request", error));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.REQUEST, null, error));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRequestReplyMessage<>(
                    REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.RESPONSE, "response", error));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.RESPONSE, null, error));
            assertThrows(IllegalArgumentException.class,
                () -> new MessagingRequestReplyMessage<>(
                    REQUEST_ID, ENDPOINT_ID, MessagingRequestReplyMessage.Type.ERROR, "private payload", error));
        }
    }

    @Test
    void onlyAllowsRequestsToProduceReplies() {
        var request = MessagingRequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, "request");
        assertThrows(NullPointerException.class, () -> request.failure(null));
        for (MessagingRequestReplyMessage<String> reply : List.of(
            request.response("response"), request.response(null), request.failure(MessagingRequestReplyMessage.Error.NO_HANDLER)
        )) {
            assertThrows(IllegalStateException.class, () -> reply.response("another response"));
            assertThrows(IllegalStateException.class, () -> reply.failure(MessagingRequestReplyMessage.Error.HANDLER_FAILED));
        }
    }
}
