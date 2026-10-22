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
package com.dbeaver.messaging.rpc;

import com.dbeaver.messaging.requestreply.MessageSubscription;
import com.dbeaver.messaging.requestreply.RequestReplyEndpoint;
import com.dbeaver.messaging.requestreply.RequestReplyException;
import com.dbeaver.messaging.requestreply.RequestReplyMessage;
import com.dbeaver.messaging.requestreply.RequestReplyOptions;
import com.dbeaver.messaging.requestreply.RequestReplyTransport;
import com.dbeaver.messaging.rpc.fixtures.PublicInheritedService;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Modifier;
import java.lang.reflect.Type;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(30)
public class RpcEndpointTest extends RpcTestSupport {
    @Test
    void servesIndependentServicesInBothDirectionsAndAcrossTopics() throws Exception {
        var leftTransport = new MemoryTransport();
        var rightTransport = new MemoryTransport();
        connect(leftTransport, rightTransport);
        try (var left = RpcEndpoint.open(leftTransport);
             var right = RpcEndpoint.open(rightTransport, OPTIONS);
             var leftService = left.register("echo", EchoService.class, value -> "left:" + value, new TestCodec());
             var rightService = right.register("echo", EchoService.class, value -> "right:" + value, new TestCodec());
             var otherService = right.register("other", EchoService.class, value -> "other:" + value, new TestCodec())) {
            EchoService fromLeft = left.proxy("echo", EchoService.class, new TestCodec());
            EchoService fromRight = right.proxy("echo", EchoService.class, new TestCodec());

            assertEquals("right:first", fromLeft.echo("first"));
            assertEquals("left:second", fromRight.echo("second"));
            assertEquals("other:third", left.proxy("other", EchoService.class, new TestCodec()).echo("third"));
            assertEquals(2, leftTransport.listeners.size());
            assertEquals(2, rightTransport.listeners.size());
        }
        assertEquals(2, leftTransport.subscriptionCloses.get());
        assertEquals(2, rightTransport.subscriptionCloses.get());
    }

    @Test
    void passesDeclaredParameterizedTypesAndNullValuesThroughEveryCodecBoundary() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("collection", CollectionService.class,
                 values -> values == null ? null : Map.of("size", values.size()), serverCodec)) {
            CollectionService proxy = client.proxy("collection", CollectionService.class, clientCodec);
            var values = new ArrayList<>(List.of("one", "two"));
            Map<String, Integer> expected = Map.of("size", 2);

            assertEquals(expected, proxy.count(values));
            assertNull(proxy.count(null));

            var operation = CollectionService.class.getMethod("count", List.class);
            Type parameterType = operation.getGenericParameterTypes()[0];
            Type returnType = operation.getGenericReturnType();
            assertEquals(List.of(new CodecCall(values, parameterType), new CodecCall(null, parameterType)), clientCodec.encoded);
            assertEquals(clientCodec.encoded, serverCodec.decoded);
            assertEquals(List.of(new CodecCall(expected, returnType), new CodecCall(null, returnType)), serverCodec.encoded);
            assertEquals(serverCodec.encoded, clientCodec.decoded);
            assertEquals("count(java.util.List)", clientTransport.next().payload().operation());
            assertNull(clientTransport.next().payload().payload());
            assertEquals(RpcMessage.Type.RESPONSE, serverTransport.next().payload().type());
            assertEquals(RpcMessage.response(null), serverTransport.next().payload());
        }
    }

    @Test
    void supportsEveryPrimitiveWithoutChangingItsDeclaredCodecType() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("primitive", PrimitiveService.class, new PrimitiveEcho(), serverCodec)) {
            PrimitiveService proxy = client.proxy("primitive", PrimitiveService.class, clientCodec);

            assertTrue(proxy.echo(true));
            assertEquals((byte) 12, proxy.echo((byte) 12));
            assertEquals((short) 123, proxy.echo((short) 123));
            assertEquals('x', proxy.echo('x'));
            assertEquals(1234, proxy.echo(1234));
            assertEquals(12345L, proxy.echo(12345L));
            assertEquals(1.25f, proxy.echo(1.25f));
            assertEquals(2.5d, proxy.echo(2.5d));

            List<Type> expected = List.of(boolean.class, byte.class, short.class, char.class,
                int.class, long.class, float.class, double.class);
            assertEquals(expected, clientCodec.encoded.stream().map(CodecCall::type).toList());
            assertEquals(clientCodec.encoded, serverCodec.decoded);
            assertEquals(clientCodec.encoded, serverCodec.encoded);
            assertEquals(clientCodec.encoded, clientCodec.decoded);
        }
    }

    @Test
    void dispatchesOverloadsAndInheritedMethodsWithTheNarrowestCovariantReturn() throws Exception {
        assertTrue(Arrays.stream(OverloadedService.class.getDeclaredMethods()).anyMatch(method ->
            method.isBridge() && method.isSynthetic() && Arrays.equals(method.getParameterTypes(), new Class<?>[] {String.class})));
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        var implementation = new OverloadedService() {
            @NotNull
            @Override
            public String convert(@NotNull String value) {
                return "text:" + value;
            }

            @NotNull
            @Override
            public String convert(int value) {
                return "number:" + value;
            }

            @NotNull
            @Override
            public String inherited(@NotNull Long value) {
                return "inherited:" + value;
            }
        };
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("overload", OverloadedService.class, implementation, serverCodec)) {
            OverloadedService proxy = client.proxy("overload", OverloadedService.class, clientCodec);
            BaseService base = proxy;

            assertEquals("text:value", base.convert("value"));
            assertEquals("text:child", proxy.convert("child"));
            assertEquals("number:42", proxy.convert(42));
            assertEquals("inherited:9", proxy.inherited(9L));

            assertEquals("convert(java.lang.String)", clientTransport.next().payload().operation());
            assertEquals("convert(java.lang.String)", clientTransport.next().payload().operation());
            assertEquals("convert(int)", clientTransport.next().payload().operation());
            assertEquals("inherited(java.lang.Long)", clientTransport.next().payload().operation());
            assertEquals(List.of(String.class, String.class, String.class, String.class),
                clientCodec.decoded.stream().map(CodecCall::type).toList());
            assertEquals(clientCodec.decoded, serverCodec.encoded);
        }
    }

    @Test
    void invokesAnOperationInheritedFromANonPublicParentInAnotherPackage() throws Exception {
        PublicInheritedService implementation = value -> "inherited:" + value;
        var operation = PublicInheritedService.class.getMethod("inherited", String.class);
        assertFalse(Modifier.isPublic(operation.getDeclaringClass().getModifiers()));
        assertNotEquals(RpcEndpoint.class.getPackageName(), operation.getDeclaringClass().getPackageName());
        assertFalse(operation.canAccess(implementation));
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("inherited", PublicInheritedService.class, implementation, new TestCodec())) {
            PublicInheritedService proxy = client.proxy("inherited", PublicInheritedService.class, new TestCodec());

            assertEquals("inherited:value", proxy.inherited("value"));
            assertEquals("inherited(java.lang.String)", clientTransport.next().payload().operation());
            assertEquals(RpcMessage.response("inherited:value"), serverTransport.next().payload());
        }
    }

    @Test
    void voidCallsWaitForHandlerCompletionAndDoNotEncodeOrDecodeAResult() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("void", VoidService.class, value -> {
                 assertEquals("work", value);
                 entered.countDown();
                 await(release);
             }, serverCodec)) {
            try {
                Future<?> pending = async(() -> {
                    client.proxy("void", VoidService.class, clientCodec).accept("work");
                    return null;
                });
                await(entered);
                assertFalse(pending.isDone());
                assertTrue(serverTransport.sent.isEmpty());

                release.countDown();

                assertNull(result(pending));
                assertEquals(RpcMessage.response(null), serverTransport.next().payload());
                assertEquals(List.of(new CodecCall("work", String.class)), clientCodec.encoded);
                assertEquals(clientCodec.encoded, serverCodec.decoded);
                assertTrue(serverCodec.encoded.isEmpty());
                assertTrue(clientCodec.decoded.isEmpty());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void reportsHandlerFailuresWithoutLeakingPrivateDetailsOrRetrying() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var calls = new AtomicInteger();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS)) {
            for (Throwable cause : List.of(new Exception("private checked detail"),
                new IllegalStateException("private runtime detail"), new AssertionError("private error detail"))) {
                try (var registration = server.register("failure", FailingService.class, value -> {
                    calls.incrementAndGet();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw (Error) cause;
                }, new TestCodec())) {
                    RpcException failure = remoteFailure(RpcMessage.Error.HANDLER_FAILED,
                        () -> client.proxy("failure", FailingService.class, new TestCodec()).fail("secret argument"));

                    assertFalse(failure.toString().contains("private"));
                    assertFalse(failure.toString().contains("secret"));
                    assertEquals(RpcMessage.failure(RpcMessage.Error.HANDLER_FAILED), serverTransport.next().payload());
                    assertEquals("secret argument", clientTransport.next().payload().payload());
                    assertTrue(clientTransport.sent.isEmpty());
                    assertTrue(serverTransport.sent.isEmpty());
                }
            }
            assertEquals(3, calls.get());
        }
    }

    @Test
    void distinguishesLocalAndRemoteCodecFailuresAtAllFourBoundariesAndRecovers() throws Exception {
        for (int boundary = 0; boundary < 4; boundary++) {
            var clientTransport = new MemoryTransport();
            var serverTransport = new MemoryTransport();
            var clientCodec = new TestCodec();
            var serverCodec = new TestCodec();
            var cause = new Exception("private codec detail");
            var calls = new AtomicInteger();
            Conversion broken = (value, type) -> {
                throw cause;
            };
            switch (boundary) {
                case 0 -> clientCodec.encoder = broken;
                case 1 -> serverCodec.decoder = broken;
                case 2 -> serverCodec.encoder = broken;
                case 3 -> clientCodec.decoder = broken;
                default -> throw new AssertionError("Unexpected codec boundary");
            }
            connect(clientTransport, serverTransport);
            try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
                 var server = RpcEndpoint.open(serverTransport, OPTIONS);
                 var registration = server.register("echo", EchoService.class, value -> {
                     calls.incrementAndGet();
                     return value;
                 }, serverCodec)) {
                EchoService proxy = client.proxy("echo", EchoService.class, clientCodec);
                if (boundary == 0 || boundary == 3) {
                    RpcException failure = failure(RpcException.Reason.CODEC_FAILED, () -> proxy.echo("secret argument"));
                    assertSame(cause, failure.getCause());
                    assertNull(failure.remoteError());
                } else {
                    RpcException failure = remoteFailure(RpcMessage.Error.CODEC_FAILED, () -> proxy.echo("secret argument"));
                    assertFalse(failure.toString().contains("private"));
                    assertEquals(RpcMessage.failure(RpcMessage.Error.CODEC_FAILED), serverTransport.next().payload());
                }
                assertEquals(boundary < 2 ? 0 : 1, calls.get());
                assertEquals(boundary == 0 ? 0 : 1, clientTransport.sent.size());

                clientCodec.encoder = (value, type) -> value;
                clientCodec.decoder = (value, type) -> value;
                serverCodec.encoder = (value, type) -> value;
                serverCodec.decoder = (value, type) -> value;
                assertEquals("recovered", proxy.echo("recovered"));
                assertEquals(boundary < 2 ? 1 : 2, calls.get());
            }
        }
    }

    @Test
    void rejectsPrimitiveNullsAndWrongDecodedArgumentTypesBeforeInvokingAService() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var codec = new TestCodec();
        var calls = new AtomicInteger();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var references = server.register("reference", EchoService.class, value -> {
                 calls.incrementAndGet();
                 return value;
             }, codec);
             var primitives = server.register("primitive", IntService.class, value -> {
                 calls.incrementAndGet();
                 return value;
             }, codec)) {
            for (Object invalid : Arrays.asList(null, "wrong primitive", 1L)) {
                codec.decoder = (value, type) -> invalid;
                remoteFailure(RpcMessage.Error.INVALID_REQUEST, () -> client.proxy("primitive", IntService.class, new TestCodec()).echo(1));
            }
            codec.decoder = (value, type) -> 42;
            remoteFailure(RpcMessage.Error.INVALID_REQUEST,
                () -> client.proxy("reference", EchoService.class, new TestCodec()).echo("value"));

            assertEquals(0, calls.get());
            assertTrue(codec.encoded.isEmpty());
        }
    }

    @Test
    void rejectsPrimitiveNullsAndWrongDecodedResultTypesAsInvalidResponses() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var codec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var references = server.register("reference", EchoService.class, value -> value, new TestCodec());
             var primitives = server.register("primitive", IntService.class, value -> value, new TestCodec())) {
            for (Object invalid : Arrays.asList(null, "wrong primitive", 1L)) {
                codec.decoder = (value, type) -> invalid;
                RpcException failure = failure(RpcException.Reason.INVALID_RESPONSE,
                    () -> client.proxy("primitive", IntService.class, codec).echo(1));
                assertNull(failure.remoteError());
            }
            codec.decoder = (value, type) -> 42;
            failure(RpcException.Reason.INVALID_RESPONSE, () -> client.proxy("reference", EchoService.class, codec).echo("value"));
        }
    }

    @Test
    void rejectsUnknownOperationsAndNonRequestEnvelopesBeforeDecoding() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var codec = new TestCodec();
        var calls = new AtomicInteger();
        connect(clientTransport, serverTransport);
        try (var client = RequestReplyEndpoint.open(clientTransport.forTopic("echo"), OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("echo", EchoService.class, value -> {
                 calls.incrementAndGet();
                 return value;
             }, codec)) {
            for (String operation : List.of("missing(java.lang.String)", "echo(int)")) {
                assertEquals(RpcMessage.failure(RpcMessage.Error.UNKNOWN_OPERATION),
                    client.request(RpcMessage.request(operation, "value")));
            }
            for (RpcMessage<Object> invalid : List.of(RpcMessage.<Object>response("not a request"),
                RpcMessage.<Object>failure(RpcMessage.Error.HANDLER_FAILED))) {
                assertEquals(RpcMessage.failure(RpcMessage.Error.INVALID_REQUEST), client.request(invalid));
            }
            assertEquals(0, calls.get());
            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
        }
    }

    @Test
    void rejectsMissingResponseEnvelopesRequestsUsedAsRepliesAndNonemptyVoidReplies() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            EchoService proxy = endpoint.proxy("echo", EchoService.class, codec);
            List<RpcMessage<Object>> invalid = Arrays.asList(null, RpcMessage.request("echo(java.lang.String)", "value"));
            for (RpcMessage<Object> reply : invalid) {
                transport.sender = request -> {
                    transport.deliver(request.response(reply));
                    return CompletableFuture.completedFuture(null);
                };
                failure(RpcException.Reason.INVALID_RESPONSE, () -> proxy.echo("value"));
            }
            transport.sender = request -> {
                transport.deliver(request.response(RpcMessage.response("unexpected void payload")));
                return CompletableFuture.completedFuture(null);
            };
            failure(RpcException.Reason.INVALID_RESPONSE, () -> endpoint.proxy("void", VoidService.class, codec).accept("value"));
            assertTrue(codec.decoded.isEmpty());
            assertEquals(3, transport.sent.size());
        }
    }

    @Test
    void scopesRegistrationHandlesByIdentityAndLetsAcceptedCallsFinishAfterUnregistering() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var originalCodec = new TestCodec();
        var replacementCodec = new TestCodec();
        originalCodec.encoder = (value, type) -> "old-codec:" + value;
        replacementCodec.encoder = (value, type) -> "new-codec:" + value;
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var other = server.register("other", EchoService.class, value -> "other:" + value, new TestCodec());
             var original = server.register("echo", EchoService.class, value -> {
                 entered.countDown();
                 await(release);
                 return "original:" + value;
             }, originalCodec)) {
            try {
                EchoService proxy = client.proxy("echo", EchoService.class, new TestCodec());
                assertThrows(IllegalStateException.class,
                    () -> server.register("echo", EchoService.class, value -> value, new TestCodec()));
                Future<String> accepted = async(() -> proxy.echo("accepted"));
                await(entered);

                original.close();
                remoteFailure(RpcMessage.Error.UNKNOWN_SERVICE, () -> proxy.echo("unregistered"));
                try (var replacement = server.register("echo", EchoService.class, value -> "replacement:" + value, replacementCodec)) {
                    original.close();
                    assertEquals("new-codec:replacement:next", proxy.echo("next"));
                    assertEquals("other:next", client.proxy("other", EchoService.class, new TestCodec()).echo("next"));
                    release.countDown();
                    assertEquals("old-codec:original:accepted", result(accepted));
                }
                remoteFailure(RpcMessage.Error.UNKNOWN_SERVICE, () -> proxy.echo("removed replacement"));
                assertEquals("other:surviving", client.proxy("other", EchoService.class, new TestCodec()).echo("surviving"));
                assertEquals(2, serverTransport.listeners.size());
                assertEquals(0, serverTransport.subscriptionCloses.get());
            } finally {
                release.countDown();
            }
        }
    }

    @Test
    void closingAnEndpointFailsPendingCallsAndClosesItsTopicSubscriptions() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            EchoService proxy = endpoint.proxy("echo", EchoService.class, codec);
            Future<String> pending = async(() -> proxy.echo("pending"));
            transport.next();

            endpoint.close();

            RpcException failure = failure(RpcException.Reason.CLOSED, pending);
            assertNull(failure.remoteError());
            failure(RpcException.Reason.CLOSED, () -> proxy.echo("closed"));
            failure(RpcException.Reason.CLOSED, () -> endpoint.proxy("echo", EchoService.class, codec));
            failure(RpcException.Reason.CLOSED, () -> endpoint.register("echo", EchoService.class, value -> value, codec));
            assertTrue(transport.listeners.isEmpty());
            assertTrue(transport.sent.isEmpty());
            assertEquals(1, transport.subscriptionCloses.get());
            assertEquals(1, codec.encoded.size());
            endpoint.close();
            assertEquals(1, transport.subscriptionCloses.get());
        }
    }

    @Test
    void failedTopicOpeningReleasesItsReservationAndAllowsRetry() throws Exception {
        var transport = new MemoryTransport();
        var attempts = new AtomicInteger();
        var expected = new RequestReplyException(RequestReplyException.Reason.DISCONNECTED);
        try (var endpoint = RpcEndpoint.<Object>open(topic -> {
            if (attempts.getAndIncrement() == 0) {
                throw expected;
            }
            return transport.forTopic(topic);
        }, OPTIONS)) {
            assertSame(expected,
                failure(RpcException.Reason.DISCONNECTED, () -> endpoint.proxy("echo", EchoService.class, new TestCodec())).getCause());
            endpoint.proxy("echo", EchoService.class, new TestCodec());
            assertEquals(2, attempts.get());
            assertEquals(1, transport.listeners.size());
        }
    }

    @Test
    void disconnectFailsPendingCallsAndFreshCallsDoNotReplayThem() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            EchoService proxy = endpoint.proxy("echo", EchoService.class, new TestCodec());
            Future<String> pending = async(() -> proxy.echo("old"));
            final var oldRequest = transport.next();

            transport.disconnect();

            RpcException failure = failure(RpcException.Reason.DISCONNECTED, pending);
            assertNull(failure.remoteError());
            assertTrue(transport.sent.isEmpty());
            Future<String> fresh = async(() -> proxy.echo("fresh"));
            var freshRequest = transport.next();
            assertEquals("fresh", freshRequest.payload().payload());
            transport.deliver(oldRequest.response(RpcMessage.response("late")));
            assertFalse(fresh.isDone());
            transport.deliver(freshRequest.response(RpcMessage.response("fresh reply")));

            assertEquals("fresh reply", result(fresh));
            assertTrue(transport.sent.isEmpty());
            assertEquals(0, transport.subscriptionCloses.get());
        }
    }

    @Test
    void usesTheConfiguredTimeoutWithoutRetryingTheEncodedRequest() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        var options = new RequestReplyOptions(Duration.ofMillis(100), 1, 1);
        try (var endpoint = RpcEndpoint.open(transport, options)) {
            RpcException failure = failure(RpcException.Reason.TIMEOUT,
                () -> endpoint.proxy("echo", EchoService.class, codec).echo("expire"));

            assertNull(failure.remoteError());
            assertEquals("expire", transport.next().payload().payload());
            assertTrue(transport.sent.isEmpty());
            assertEquals(List.of(new CodecCall("expire", String.class)), codec.encoded);
            assertTrue(codec.decoded.isEmpty());
        }
    }

    @Test
    void mapsSendFailureAndPendingOverloadWithoutSendingAnExtraRequest() throws Exception {
        var transport = new MemoryTransport();
        var cause = new IllegalStateException("send failed");
        var options = new RequestReplyOptions(WAIT, 1, 1);
        transport.sender = request -> CompletableFuture.failedFuture(cause);
        try (var endpoint = RpcEndpoint.open(transport, options)) {
            EchoService proxy = endpoint.proxy("echo", EchoService.class, new TestCodec());
            RpcException failed = failure(RpcException.Reason.SEND_FAILED, () -> proxy.echo("failed"));
            assertSame(cause, assertInstanceOf(RequestReplyException.class, failed.getCause()).getCause());
            assertNull(failed.remoteError());
            transport.next();
            assertTrue(transport.sent.isEmpty());

            transport.sender = request -> CompletableFuture.completedFuture(null);
            final Future<String> pending = async(() -> proxy.echo("pending"));
            var request = transport.next();
            failure(RpcException.Reason.OVERLOADED, () -> proxy.echo("excess"));
            assertTrue(transport.sent.isEmpty());
            transport.deliver(request.response(RpcMessage.response("completed")));
            assertEquals("completed", result(pending));
        }
    }

    @Test
    void mapsRequestReplyRemoteFailuresWithoutInventingAnRpcErrorCode() throws Exception {
        var transport = new MemoryTransport();
        transport.sender = request -> {
            transport.deliver(request.failure(RequestReplyMessage.Error.OVERLOADED));
            return CompletableFuture.completedFuture(null);
        };
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            RpcException failure = failure(RpcException.Reason.REMOTE_ERROR,
                () -> endpoint.proxy("echo", EchoService.class, new TestCodec()).echo("value"));
            assertNull(failure.remoteError());
            assertEquals(RequestReplyMessage.Error.OVERLOADED,
                assertInstanceOf(RequestReplyException.class, failure.getCause()).remoteError());
            assertEquals(1, transport.sent.size());
        }
    }

    @Test
    void restoresTheCallerInterruptFlagWhenAPendingRequestIsInterrupted() throws Exception {
        var transport = new MemoryTransport();
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            EchoService proxy = endpoint.proxy("echo", EchoService.class, new TestCodec());
            var pending = new FutureTask<>(() -> {
                RpcException failure = failure(RpcException.Reason.INTERRUPTED, () -> proxy.echo("interrupt"));
                assertInstanceOf(InterruptedException.class, failure.getCause());
                assertNull(failure.remoteError());
                return Thread.currentThread().isInterrupted();
            });
            Thread caller = start(pending);
            transport.next();

            caller.interrupt();

            assertEquals(Boolean.TRUE, result(pending));
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void alreadyInterruptedCallersKeepTheirFlagWithoutInvokingTheCodecOrSending() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            EchoService proxy = endpoint.proxy("echo", EchoService.class, codec);
            Future<Boolean> flag = async(() -> {
                Thread.currentThread().interrupt();
                try {
                    failure(RpcException.Reason.INTERRUPTED, () -> proxy.echo("value"));
                    return Thread.currentThread().isInterrupted();
                } finally {
                    Thread.interrupted();
                }
            });

            assertEquals(Boolean.TRUE, result(flag));
            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void restoresTheCallerInterruptFlagWhenEitherLocalCodecBoundaryIsInterrupted() throws Exception {
        for (boolean encoding : List.of(false, true)) {
            var transport = new MemoryTransport();
            var codec = new TestCodec();
            Conversion interrupted = (value, type) -> {
                throw new InterruptedException("codec interrupted");
            };
            if (encoding) {
                codec.encoder = interrupted;
            } else {
                codec.decoder = interrupted;
            }
            transport.sender = request -> {
                transport.deliver(request.response(RpcMessage.response("value")));
                return CompletableFuture.completedFuture(null);
            };
            try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
                Future<Boolean> flag = async(() -> {
                    failure(RpcException.Reason.INTERRUPTED, () -> endpoint.proxy("echo", EchoService.class, codec).echo("value"));
                    return Thread.currentThread().isInterrupted();
                });

                assertEquals(Boolean.TRUE, result(flag));
                assertEquals(encoding ? 0 : 1, transport.sent.size());
            }
        }
    }

    @Test
    void processesParallelCallsAndConcurrentDecodesWithOutOfOrderCompletion() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var serverCodec = new TestCodec();
        var decoding = new CountDownLatch(4);
        var releaseDecoding = new CountDownLatch(1);
        var entered = new CountDownLatch(4);
        var release = new LinkedHashMap<String, CountDownLatch>();
        for (String value : List.of("one", "two", "three", "four")) {
            release.put(value, new CountDownLatch(1));
        }
        serverCodec.decoder = (value, type) -> {
            decoding.countDown();
            await(releaseDecoding);
            return value;
        };
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("echo", EchoService.class, value -> {
                 entered.countDown();
                 await(release.get(value));
                 return "reply:" + value;
             }, serverCodec)) {
            try {
                EchoService proxy = client.proxy("echo", EchoService.class, new TestCodec());
                var results = new LinkedHashMap<String, Future<String>>();
                for (String value : release.keySet()) {
                    results.put(value, async(() -> proxy.echo(value)));
                }
                var order = new ArrayList<String>();
                for (int index = 0; index < results.size(); index++) {
                    order.add(assertInstanceOf(String.class, clientTransport.next().payload().payload()));
                }
                await(decoding);
                releaseDecoding.countDown();
                await(entered);
                assertTrue(results.values().stream().noneMatch(Future::isDone));

                for (int index = order.size() - 1; index >= 0; index--) {
                    String value = order.get(index);
                    release.get(value).countDown();
                    assertEquals("reply:" + value, result(results.get(value)));
                    assertEquals(RpcMessage.response("reply:" + value), serverTransport.next().payload());
                }
                assertEquals(4, serverCodec.decoded.size());
                assertEquals(4, serverCodec.encoded.size());
                assertTrue(serverTransport.sent.isEmpty());
            } finally {
                releaseDecoding.countDown();
                release.values().forEach(CountDownLatch::countDown);
            }
        }
    }

    @Test
    void objectMethodsRemainLocalAndUseProxyIdentityEvenAfterClosing() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            EchoService first = endpoint.proxy("echo", EchoService.class, codec);
            EchoService second = endpoint.proxy("echo", EchoService.class, codec);

            assertTrue(first.equals(first));
            assertFalse(first.equals(second));
            assertFalse(first.equals(null));
            assertFalse(first.equals(new Object()));
            assertEquals(System.identityHashCode(first), first.hashCode());
            assertTrue(first.toString().contains("echo"));
            assertTrue(first.toString().contains(EchoService.class.getName()));
            assertNotEquals(first, second);
            endpoint.close();
            assertTrue(first.equals(first));
            assertEquals(System.identityHashCode(first), first.hashCode());
            assertTrue(first.toString().contains("echo"));

            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void rejectsInvalidTopicsNullArgumentsAndOptionsBeforeSubscribingOrSending() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        assertThrows(NullPointerException.class, () -> RpcEndpoint.open(transport, (RequestReplyOptions) null));
        assertThrows(NullPointerException.class, () -> RpcEndpoint.open(transport, null, OPTIONS));
        assertThrows(NullPointerException.class, () -> RpcEndpoint.open(null, OPTIONS));
        assertTrue(transport.listeners.isEmpty());

        try (var endpoint = RpcEndpoint.open(transport, OPTIONS)) {
            for (String topic : List.of("", " ", "\n", "t".repeat(257))) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy(topic, EchoService.class, codec));
                assertThrows(IllegalArgumentException.class, () -> endpoint.register(topic, EchoService.class, value -> value, codec));
            }
            assertThrows(NullPointerException.class, () -> endpoint.proxy(null, EchoService.class, codec));
            assertThrows(NullPointerException.class, () -> endpoint.proxy("echo", null, codec));
            assertThrows(NullPointerException.class, () -> endpoint.proxy("echo", EchoService.class, null));
            assertThrows(NullPointerException.class, () -> endpoint.register(null, EchoService.class, value -> value, codec));
            assertThrows(NullPointerException.class, () -> endpoint.register("echo", EchoService.class, null, codec));
            assertThrows(NullPointerException.class, () -> endpoint.register("echo", EchoService.class, value -> value, null));
            assertTrue(transport.listeners.isEmpty());
            assertTrue(transport.sent.isEmpty());
            assertTrue(codec.encoded.isEmpty());
        }
    }

    @Test
    void propagatesSubscriptionFailureWithoutSending() {
        var expected = new RequestReplyException(RequestReplyException.Reason.DISCONNECTED);
        var transport = new RequestReplyTransport<RpcMessage<Object>>() {
            @NotNull
            @Override
            public MessageSubscription subscribe(
                @NotNull UUID endpointId,
                @NotNull Listener<RpcMessage<Object>> listener
            ) throws RequestReplyException {
                throw expected;
            }

            @NotNull
            @Override
            public CompletionStage<Void> send(@NotNull RequestReplyMessage<RpcMessage<Object>> message) {
                throw new AssertionError("Subscription failure must prevent sending");
            }
        };

        try (var endpoint = RpcEndpoint.open(topic -> transport, OPTIONS)) {
            assertSame(expected,
                failure(RpcException.Reason.DISCONNECTED, () -> endpoint.proxy("echo", EchoService.class, new TestCodec())).getCause());
        }
    }

    /** Defines nullable reference calls and redeclares local object methods. */
    public interface EchoService {
        @Nullable
        String echo(@Nullable String value) throws InterruptedException;

        boolean equals(@Nullable Object value);

        int hashCode();

        @NotNull
        String toString();
    }

    /** Defines a concrete parameterized request and response. */
    public interface CollectionService {
        @Nullable
        Map<String, Integer> count(@Nullable List<String> values);
    }

    /** Defines synchronous completion without a result. */
    public interface VoidService {
        void accept(@Nullable String value) throws InterruptedException;
    }

    /** Defines an operation that may fail with a checked exception. */
    public interface FailingService {
        @NotNull
        String fail(@NotNull String value) throws Exception;
    }

    /** Defines an integer request and response. */
    public interface IntService {
        int echo(int value);
    }

    /** Defines inherited and covariant remote operations. */
    public interface BaseService {
        @NotNull
        CharSequence convert(@NotNull String value);

        @NotNull
        String inherited(@NotNull Long value);
    }

    /** Narrows an inherited return type and adds a primitive overload. */
    public interface OverloadedService extends BaseService {
        @NotNull
        @Override
        String convert(@NotNull String value);

        @NotNull
        String convert(int value);
    }

    /** Defines overloads for every primitive request and response. */
    public interface PrimitiveService {
        boolean echo(boolean value);

        byte echo(byte value);

        short echo(short value);

        char echo(char value);

        int echo(int value);

        long echo(long value);

        float echo(float value);

        double echo(double value);
    }

    private static final class PrimitiveEcho implements PrimitiveService {
        @Override
        public boolean echo(boolean value) {
            return value;
        }

        @Override
        public byte echo(byte value) {
            return value;
        }

        @Override
        public short echo(short value) {
            return value;
        }

        @Override
        public char echo(char value) {
            return value;
        }

        @Override
        public int echo(int value) {
            return value;
        }

        @Override
        public long echo(long value) {
            return value;
        }

        @Override
        public float echo(float value) {
            return value;
        }

        @Override
        public double echo(double value) {
            return value;
        }
    }
}
