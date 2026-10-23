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
class RequestReplyMessageTest {
    private static final UUID REQUEST_ID = UUID.randomUUID();
    private static final UUID ENDPOINT_ID = UUID.randomUUID();

    @Test
    void preservesTheOpaquePayloadAndCorrelationWhenCreatingReplies() {
        Object payload = new Object();
        RequestReplyMessage<Object> request = RequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, payload);

        assertEquals(REQUEST_ID, request.requestId());
        assertEquals(ENDPOINT_ID, request.replyTo());
        assertEquals(RequestReplyMessage.Type.REQUEST, request.type());
        assertSame(payload, request.payload());
        assertNull(request.error());

        Object result = new Object();
        RequestReplyMessage<Object> response = request.response(result);

        assertEquals(REQUEST_ID, response.requestId());
        assertEquals(ENDPOINT_ID, response.replyTo());
        assertEquals(RequestReplyMessage.Type.RESPONSE, response.type());
        assertSame(result, response.payload());
        assertNull(response.error());
        assertSame(payload, request.payload());
    }

    @Test
    void permitsANullResponseWithoutTurningItIntoAnError() {
        RequestReplyMessage<String> response = RequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, "request").response(null);

        assertEquals(REQUEST_ID, response.requestId());
        assertEquals(ENDPOINT_ID, response.replyTo());
        assertEquals(RequestReplyMessage.Type.RESPONSE, response.type());
        assertNull(response.payload());
        assertNull(response.error());
    }

    @Test
    void exposesOnlyTheErrorCodeAndOriginalCorrelationInFailures() {
        var request = RequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, "private request");
        for (RequestReplyMessage.Error error : RequestReplyMessage.Error.values()) {
            RequestReplyMessage<String> failure = request.failure(error);

            assertEquals(REQUEST_ID, failure.requestId());
            assertEquals(ENDPOINT_ID, failure.replyTo());
            assertEquals(RequestReplyMessage.Type.ERROR, failure.type());
            assertEquals(error, failure.error());
            assertNull(failure.payload());
        }
    }

    @Test
    void rejectsMissingCorrelationAndType() {
        assertThrows(NullPointerException.class,
            () -> new RequestReplyMessage<>(null, ENDPOINT_ID, RequestReplyMessage.Type.RESPONSE, null, null));
        assertThrows(NullPointerException.class,
            () -> new RequestReplyMessage<>(REQUEST_ID, null, RequestReplyMessage.Type.RESPONSE, null, null));
        assertThrows(NullPointerException.class,
            () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, null, null, null));
        assertThrows(NullPointerException.class, () -> RequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, null));
    }

    @Test
    void rejectsMalformedPayloadAndErrorCombinations() {
        assertThrows(IllegalArgumentException.class,
            () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.REQUEST, null, null));
        assertThrows(IllegalArgumentException.class,
            () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.ERROR, null, null));
        for (RequestReplyMessage.Error error : RequestReplyMessage.Error.values()) {
            assertThrows(IllegalArgumentException.class,
                () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.REQUEST, "request", error));
            assertThrows(IllegalArgumentException.class,
                () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.REQUEST, null, error));
            assertThrows(IllegalArgumentException.class,
                () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.RESPONSE, "response", error));
            assertThrows(IllegalArgumentException.class,
                () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.RESPONSE, null, error));
            assertThrows(IllegalArgumentException.class,
                () -> new RequestReplyMessage<>(REQUEST_ID, ENDPOINT_ID, RequestReplyMessage.Type.ERROR, "private payload", error));
        }
    }

    @Test
    void onlyAllowsRequestsToProduceReplies() {
        var request = RequestReplyMessage.request(REQUEST_ID, ENDPOINT_ID, "request");
        assertThrows(NullPointerException.class, () -> request.failure(null));
        for (RequestReplyMessage<String> reply : List.of(
            request.response("response"), request.response(null), request.failure(RequestReplyMessage.Error.NO_HANDLER)
        )) {
            assertThrows(IllegalStateException.class, () -> reply.response("another response"));
            assertThrows(IllegalStateException.class, () -> reply.failure(RequestReplyMessage.Error.HANDLER_FAILED));
        }
    }
}
