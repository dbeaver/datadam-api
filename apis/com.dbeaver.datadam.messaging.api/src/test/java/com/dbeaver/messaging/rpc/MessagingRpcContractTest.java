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

import com.dbeaver.messaging.requestreply.MessagingRequestReplyEndpoint;
import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
class MessagingRpcContractTest extends MessagingRpcTestSupport {
    @Test
    void rejectsInvalidContractsBeforeSendingAndLeavesTheTopicAvailable() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = MessagingRpcEndpoint.open(transport, OPTIONS)) {
            for (Class<?> type : List.of(
                String.class, HiddenService.class, SealedService.class, GenericService.class, RawGenericService.class,
                EmptyService.class, StaticOnlyService.class, ObjectOnlyService.class, DefaultService.class,
                GenericMethodService.class, FutureService.class, StageService.class, CompletableService.class
            )) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("service", type, codec), type.getName());
                assertThrows(IllegalArgumentException.class, () -> endpoint.register("service", type, null, codec), type.getName());
            }
            assertThrows(IllegalArgumentException.class, () -> endpoint.register("service", DefaultService.class, value -> value, codec));
            try (var registration = endpoint.register("service", StaticService.class, value -> value, codec)) {
                assertTrue(transport.sent.isEmpty());
                assertTrue(codec.encoded.isEmpty());
                assertTrue(codec.decoded.isEmpty());
            }
        }
    }

    @Test
    void invokesZeroAndTwoArgumentOperationsWithTheDeclaredArgumentLists() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = MessagingRpcEndpoint.open(clientTransport, OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var zero = server.register("zero", NoArgumentService.class, () -> "zero", serverCodec);
             var two = server.register("two", TwoArgumentService.class, (first, second) -> first + ":" + second, serverCodec)) {
            assertEquals("zero", client.proxy("zero", NoArgumentService.class, clientCodec).echo());
            assertEquals(MessagingRpcMessage.request("echo()", List.of()), clientTransport.next().payload());
            assertTrue(clientCodec.encoded.isEmpty());
            assertTrue(serverCodec.decoded.isEmpty());

            assertEquals("first:second", client.proxy("two", TwoArgumentService.class, clientCodec).echo("first", "second"));
            assertEquals(MessagingRpcMessage.request("echo(java.lang.String,java.lang.String)", List.of("first", "second")),
                clientTransport.next().payload());
            assertEquals(List.of(new CodecCall("first", String.class), new CodecCall("second", String.class)), clientCodec.encoded);
            assertEquals(clientCodec.encoded, serverCodec.decoded);
            assertEquals(List.of(new CodecCall("zero", String.class), new CodecCall("first:second", String.class)), serverCodec.encoded);
            assertEquals(serverCodec.encoded, clientCodec.decoded);
        }
    }

    @Test
    void preservesEachArgumentsGenericTypeAndNullableReferenceValue() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        var calls = new AtomicInteger();
        connect(clientTransport, serverTransport);
        try (var client = MessagingRpcEndpoint.open(clientTransport, OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("generic", ParameterizedArgumentsService.class, (values, groups, offset) -> {
                 calls.incrementAndGet();
                 return values == null || groups == null ? null : Map.of("total", values.size() + groups.get("group").size() + offset);
             }, serverCodec)) {
            ParameterizedArgumentsService proxy = client.proxy("generic", ParameterizedArgumentsService.class, clientCodec);
            List<String> values = List.of("one", "two");
            Map<String, List<Integer>> groups = Map.of("group", List.of(3, 4, 5));
            Map<String, Integer> expected = Map.of("total", 12);

            assertEquals(expected, proxy.count(values, groups, 7));
            assertNull(proxy.count(null, groups, 8));
            assertNull(proxy.count(values, null, 9));
            assertEquals(3, calls.get());

            var operation = ParameterizedArgumentsService.class.getMethod("count", List.class, Map.class, int.class);
            var parameterTypes = operation.getGenericParameterTypes();
            assertEquals("java.util.List<java.lang.String>", parameterTypes[0].getTypeName());
            assertEquals("java.util.Map<java.lang.String, java.util.List<java.lang.Integer>>", parameterTypes[1].getTypeName());
            assertEquals(int.class, parameterTypes[2]);
            assertEquals(List.of(
                new CodecCall(values, parameterTypes[0]), new CodecCall(groups, parameterTypes[1]), new CodecCall(7, int.class),
                new CodecCall(null, parameterTypes[0]), new CodecCall(groups, parameterTypes[1]), new CodecCall(8, int.class),
                new CodecCall(values, parameterTypes[0]), new CodecCall(null, parameterTypes[1]), new CodecCall(9, int.class)
            ), clientCodec.encoded);
            assertEquals(clientCodec.encoded, serverCodec.decoded);
            var returnType = operation.getGenericReturnType();
            assertEquals(List.of(new CodecCall(expected, returnType), new CodecCall(null, returnType), new CodecCall(null, returnType)),
                serverCodec.encoded);
            assertEquals(serverCodec.encoded, clientCodec.decoded);
            assertEquals(List.of(values, groups, 7), clientTransport.next().payload().arguments());
            assertEquals(Arrays.asList(null, groups, 8), clientTransport.next().payload().arguments());
            assertEquals(Arrays.asList(values, null, 9), clientTransport.next().payload().arguments());
        }
    }

    @Test
    void treatsVarargsAsOneFinalArrayArgumentIncludingEmptyAndNullArrays() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = MessagingRpcEndpoint.open(clientTransport, OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var arrays = server.register("array", VarargsService.class,
                 values -> values == null ? "null" : String.join(",", values), serverCodec);
             var prefixed = server.register("prefixed", PrefixedVarargsService.class,
                 (prefix, values) -> prefix + String.join(",", values), serverCodec)) {
            VarargsService proxy = client.proxy("array", VarargsService.class, clientCodec);
            String[] values = {"one", "two"};
            assertEquals("one,two", proxy.echo(values));
            assertEquals("", proxy.echo());
            assertEquals("null", proxy.echo((String[]) null));
            assertEquals("prefix:three,four",
                client.proxy("prefixed", PrefixedVarargsService.class, clientCodec).echo("prefix:", "three", "four"));

            var arrayRequest = clientTransport.next().payload();
            assertEquals("echo(java.lang.String[])", arrayRequest.operation());
            assertEquals(1, arrayRequest.arguments().size());
            assertSame(values, arrayRequest.arguments().get(0));
            var emptyRequest = clientTransport.next().payload();
            assertEquals("echo(java.lang.String[])", emptyRequest.operation());
            assertEquals(1, emptyRequest.arguments().size());
            assertEquals(0, assertInstanceOf(String[].class, emptyRequest.arguments().get(0)).length);
            assertEquals(Arrays.asList((Object) null), clientTransport.next().payload().arguments());
            var prefixedRequest = clientTransport.next().payload();
            assertEquals("echo(java.lang.String,java.lang.String[])", prefixedRequest.operation());
            assertEquals(2, prefixedRequest.arguments().size());
            assertEquals("prefix:", prefixedRequest.arguments().get(0));
            assertEquals(List.of("three", "four"), Arrays.asList(assertInstanceOf(String[].class, prefixedRequest.arguments().get(1))));
            assertEquals(List.of(String[].class, String[].class, String[].class, String.class, String[].class),
                clientCodec.encoded.stream().map(CodecCall::type).toList());
            assertEquals(clientCodec.encoded, serverCodec.decoded);
            assertEquals(serverCodec.encoded, clientCodec.decoded);
        }
    }

    @Test
    void dispatchesOverloadsByEveryParameterAndArityWhileAcceptingCovariantBridges() throws Exception {
        for (int arity : List.of(0, 2)) {
            assertTrue(Arrays.stream(OverloadedArgumentsService.class.getDeclaredMethods()).anyMatch(method ->
                method.isBridge() && method.isSynthetic() && method.getParameterCount() == arity));
        }
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        connect(clientTransport, serverTransport);
        var implementation = new OverloadedArgumentsService() {
            @NotNull
            @Override
            public String echo() {
                return "zero";
            }

            @NotNull
            @Override
            public String echo(@NotNull String first) {
                return "one:" + first;
            }

            @NotNull
            @Override
            public String echo(@NotNull String first, int second) {
                return "int:" + first + ":" + second;
            }

            @NotNull
            @Override
            public String echo(@NotNull String first, long second) {
                return "long:" + first + ":" + second;
            }

            @NotNull
            @Override
            public String echo(@NotNull String first, int second, @NotNull String third) {
                return "three:" + first + ":" + second + ":" + third;
            }
        };
        try (var client = MessagingRpcEndpoint.open(clientTransport, OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("overloads", OverloadedArgumentsService.class, implementation, new TestCodec())) {
            OverloadedArgumentsService proxy = client.proxy("overloads", OverloadedArgumentsService.class, new TestCodec());
            BaseArgumentsService base = proxy;

            assertEquals("zero", base.echo());
            assertEquals("zero", proxy.echo());
            assertEquals("one:value", proxy.echo("value"));
            assertEquals("int:base:1", base.echo("base", 1));
            assertEquals("int:value:2", proxy.echo("value", 2));
            assertEquals("long:value:3", proxy.echo("value", 3L));
            assertEquals("three:value:4:last", proxy.echo("value", 4, "last"));

            assertEquals("echo()", clientTransport.next().payload().operation());
            assertEquals("echo()", clientTransport.next().payload().operation());
            assertEquals("echo(java.lang.String)", clientTransport.next().payload().operation());
            assertEquals("echo(java.lang.String,int)", clientTransport.next().payload().operation());
            assertEquals("echo(java.lang.String,int)", clientTransport.next().payload().operation());
            assertEquals("echo(java.lang.String,long)", clientTransport.next().payload().operation());
            assertEquals("echo(java.lang.String,int,java.lang.String)", clientTransport.next().payload().operation());
        }
    }

    @Test
    void rejectsWrongArgumentCountsBeforeDecodingAndFreshCallsRecover() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var codec = new TestCodec();
        var calls = new AtomicInteger();
        connect(clientTransport, serverTransport);
        try (var client = MessagingRequestReplyEndpoint.open(clientTransport.forTopic("arguments"), OPTIONS);
             var zeroClient = MessagingRequestReplyEndpoint.open(clientTransport.forTopic("zero"), OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("arguments", MixedArgumentsService.class, (first, second, third) -> {
                 calls.incrementAndGet();
                 return first + ":" + second + ":" + third;
             }, codec);
             var zero = server.register("zero", NoArgumentService.class, () -> {
                 calls.incrementAndGet();
                 return "zero";
             }, codec)) {
            List<List<Object>> invalidArguments = List.of(List.of(), List.of("first"), List.of("first", 2),
                List.of("first", 2, "third", "extra"));
            for (List<Object> arguments : invalidArguments) {
                int beforeCalls = calls.get();
                int beforeDecoded = codec.decoded.size();
                final int beforeEncoded = codec.encoded.size();
                assertEquals(MessagingRpcMessage.failure(MessagingRpcMessage.Error.INVALID_REQUEST),
                    client.request(MessagingRpcMessage.request("echo(java.lang.String,int,java.lang.String)", arguments)));
                assertEquals(beforeCalls, calls.get());
                assertEquals(beforeDecoded, codec.decoded.size());
                assertEquals(beforeEncoded, codec.encoded.size());

                assertEquals(MessagingRpcMessage.response("first:2:third"),
                    client.request(MessagingRpcMessage.request("echo(java.lang.String,int,java.lang.String)",
                        List.of("first", 2, "third"))));
                assertEquals(beforeCalls + 1, calls.get());
                assertEquals(beforeDecoded + 3, codec.decoded.size());
            }
            int beforeCalls = calls.get();
            int beforeDecoded = codec.decoded.size();
            assertEquals(MessagingRpcMessage.failure(MessagingRpcMessage.Error.INVALID_REQUEST),
                zeroClient.request(MessagingRpcMessage.request("echo()", List.of("extra"))));
            assertEquals(beforeCalls, calls.get());
            assertEquals(beforeDecoded, codec.decoded.size());
            assertEquals(MessagingRpcMessage.response("zero"), zeroClient.request(MessagingRpcMessage.request("echo()", List.of())));
            assertEquals(beforeCalls + 1, calls.get());
            assertEquals(beforeDecoded, codec.decoded.size());
        }
    }

    @Test
    void rejectsWrongDecodedValuesAtEveryArgumentPositionAndFreshCallsRecover() throws Exception {
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var codec = new TestCodec();
        var calls = new AtomicInteger();
        connect(clientTransport, serverTransport);
        try (var client = MessagingRequestReplyEndpoint.open(clientTransport.forTopic("arguments"), OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var registration = server.register("arguments", MixedArgumentsService.class, (first, second, third) -> {
                 calls.incrementAndGet();
                 return first + ":" + second + ":" + third;
             }, codec)) {
            List<List<Object>> invalidArguments = List.of(List.of(42, 2, "third"), Arrays.asList("first", null, "third"),
                List.of("first", "wrong primitive", "third"), List.of("first", 2L, "third"), List.of("first", 2, 42));
            for (List<Object> arguments : invalidArguments) {
                int beforeCalls = calls.get();
                final int beforeEncoded = codec.encoded.size();
                assertEquals(MessagingRpcMessage.failure(MessagingRpcMessage.Error.INVALID_REQUEST),
                    client.request(MessagingRpcMessage.request("echo(java.lang.String,int,java.lang.String)", arguments)));
                assertEquals(beforeCalls, calls.get());
                assertEquals(beforeEncoded, codec.encoded.size());

                assertEquals(MessagingRpcMessage.response("null:2:null"),
                    client.request(MessagingRpcMessage.request("echo(java.lang.String,int,java.lang.String)",
                        Arrays.asList(null, 2, null))));
                assertEquals(beforeCalls + 1, calls.get());
                assertEquals(beforeEncoded + 1, codec.encoded.size());
            }
        }
    }

    @Test
    void rejectsUnresolvedTypeVariablesInParameterizedOwnerTypes() {
        assertThrows(IllegalArgumentException.class, () -> MessagingRpcContract.create(RawOwnedGenericService.class));
    }

    @Test
    void rejectsGenericParentBridgesWhoseErasedArgumentHasNoConcreteOperation() throws Exception {
        var bridge = GenericBridgeChild.class.getDeclaredMethod("echo", Object.class);
        assertTrue(bridge.isBridge());
        assertTrue(bridge.isSynthetic());
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = MessagingRpcEndpoint.open(transport, OPTIONS)) {
            assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("bridge", GenericBridgeChild.class, codec));
            assertThrows(IllegalArgumentException.class,
                () -> endpoint.register("bridge", GenericBridgeChild.class, value -> value, codec));
            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void rejectsGenericParentBridgesWhoseLaterErasedArgumentHasNoConcreteOperation() throws Exception {
        var bridge = GenericPairBridgeChild.class.getDeclaredMethod("echo", String.class, Object.class);
        assertTrue(bridge.isBridge());
        assertTrue(bridge.isSynthetic());
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = MessagingRpcEndpoint.open(transport, OPTIONS)) {
            assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("bridge", GenericPairBridgeChild.class, codec));
            assertThrows(IllegalArgumentException.class,
                () -> endpoint.register("bridge", GenericPairBridgeChild.class, (prefix, value) -> prefix + value, codec));
            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void rejectsUnrelatedParentsWithAmbiguousGenericParameterOrReturnTypesBeforeSending() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = MessagingRpcEndpoint.open(transport, OPTIONS)) {
            for (Class<?> type : List.of(AmbiguousListCounter.class, AmbiguousListLoader.class)) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("ambiguous", type, codec), type.getName());
                assertThrows(IllegalArgumentException.class, () -> endpoint.register("ambiguous", type, null, codec), type.getName());
            }
            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void selectsTheUniquelyNarrowerGenericReturnAcrossAllParentDeclarationOrders() throws Exception {
        var expectedType = ArrayListLoader.class.getDeclaredMethod("load", String.class).getGenericReturnType();
        assertEquals("java.util.ArrayList<java.lang.String>", expectedType.getTypeName());

        for (Class<?> type : List.of(WideNarrowArrayLoader.class, WideArrayNarrowLoader.class,
            NarrowWideArrayLoader.class, NarrowArrayWideLoader.class, ArrayWideNarrowLoader.class, ArrayNarrowWideLoader.class)) {
            var operation = MessagingRpcContract.create(type).operation("load(java.lang.String)");

            assertNotNull(operation, type.getName());
            assertEquals(ArrayList.class, operation.getReturnType(), type.getName());
            assertEquals(expectedType, operation.getGenericReturnType(), type.getName());
        }
    }

    @Test
    void explicitChildRedeclarationsResolveGenericTypesForCallsThroughParentAndChildReferences() throws Exception {
        var count = ResolvedListCounter.class.getDeclaredMethod("count", List.class);
        var load = ResolvedListLoader.class.getDeclaredMethod("load", String.class);
        assertEquals(count, MessagingRpcContract.create(ResolvedListCounter.class).operation("count(java.util.List)"));
        assertEquals(load, MessagingRpcContract.create(ResolvedListLoader.class).operation("load(java.lang.String)"));
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = MessagingRpcEndpoint.open(clientTransport, OPTIONS);
             var server = MessagingRpcEndpoint.open(serverTransport, OPTIONS);
             var counter = server.register("count", ResolvedListCounter.class, List::size, serverCodec);
             var loader = server.register("load", ResolvedListLoader.class, List::of, serverCodec)) {
            ResolvedListCounter childCounter = client.proxy("count", ResolvedListCounter.class, clientCodec);
            TypedListCounter typedCounter = childCounter;
            assertEquals(2, typedCounter.count(List.of("one", "two")));
            assertEquals(1, childCounter.count(List.of("child")));

            ResolvedListLoader childLoader = client.proxy("load", ResolvedListLoader.class, clientCodec);
            WideListLoader wideLoader = childLoader;
            NarrowListLoader narrowLoader = childLoader;
            assertEquals(List.of("wide"), wideLoader.load("wide"));
            assertEquals(List.of("narrow"), narrowLoader.load("narrow"));
            assertEquals(List.of("child"), childLoader.load("child"));

            assertEquals(List.of(List.class, List.class, String.class, String.class, String.class),
                clientCodec.encoded.stream().map(CodecCall::type).toList());
            assertEquals(clientCodec.encoded, serverCodec.decoded);
            var returnType = load.getGenericReturnType();
            assertEquals("java.util.List<java.lang.String>", returnType.getTypeName());
            assertEquals(List.of(int.class, int.class, returnType, returnType, returnType),
                serverCodec.encoded.stream().map(CodecCall::type).toList());
            assertEquals(serverCodec.encoded, clientCodec.decoded);
        }
    }

    @Test
    void ignoresStaticMethodsAndKeepsConcreteDeclaredTypes() throws Exception {
        MessagingRpcContract<StaticService> contract = MessagingRpcContract.create(StaticService.class);
        assertEquals(StaticService.class.getMethod("echo", String.class), contract.operation("echo(java.lang.String)"));
        assertNull(contract.operation("ignored()"));

        MessagingRpcContract<ParameterizedService> parameterized = MessagingRpcContract.create(ParameterizedService.class);
        var method = ParameterizedService.class.getMethod("echo", List.class);
        assertEquals(method, parameterized.operation("echo(java.util.List)"));
        assertEquals(method.getGenericParameterTypes()[0], parameterized.operation("echo(java.util.List)").getGenericParameterTypes()[0]);
        assertEquals(method.getGenericReturnType(), parameterized.operation("echo(java.util.List)").getGenericReturnType());
    }

    @Test
    void prefersTheConcreteParameterizedReturnRedeclaredByAChildContract() throws Exception {
        MessagingRpcContract<NumberService> contract = MessagingRpcContract.create(NumberService.class);

        assertEquals(NumberService.class.getDeclaredMethod("numbers", String.class), contract.operation("numbers(java.lang.String)"));
        assertEquals("java.util.List<java.lang.Integer>",
            contract.operation("numbers(java.lang.String)").getGenericReturnType().getTypeName());
    }

    interface HiddenService {
        @NotNull
        String echo(@NotNull String value);
    }

    /** Defines a sealed contract that cannot be proxied. */
    public sealed interface SealedService permits PermittedService {
        @NotNull
        String echo(@NotNull String value);
    }

    /** Supplies a permitted subtype for the sealed fixture. */
    public non-sealed interface PermittedService extends SealedService {
    }

    /** Defines an unresolved generic operation. */
    public interface GenericService<T> {
        @NotNull
        T echo(@NotNull T value);
    }

    /** Specializes a generic argument and produces an erased argument bridge. */
    public interface GenericBridgeChild extends GenericService<String> {
        @NotNull
        @Override
        String echo(@NotNull String value);
    }

    public interface GenericPairService<T> {
        @NotNull
        String echo(@NotNull String prefix, @NotNull T value);
    }

    public interface GenericPairBridgeChild extends GenericPairService<String> {
        @NotNull
        @Override
        String echo(@NotNull String prefix, @NotNull String value);
    }

    /** Inherits unresolved generic operation types. */
    @SuppressWarnings("rawtypes")
    public interface RawGenericService extends GenericService {
    }

    public static final class GenericOwner<T> {
        public final class Value<V> {
        }
    }

    /** Uses an unresolved type in a parameterized owner. */
    public interface OwnedGenericService<T> {
        @NotNull
        String echo(@NotNull GenericOwner<T>.Value<String> value);
    }

    /** Inherits an unresolved parameterized owner type. */
    @SuppressWarnings("rawtypes")
    public interface RawOwnedGenericService extends OwnedGenericService {
    }

    /** Declares no remote operations. */
    public interface EmptyService {
    }

    /** Declares only a static operation. */
    public interface StaticOnlyService {
        @NotNull
        static String ignored() {
            return "local";
        }
    }

    /** Declares only local object operations. */
    public interface ObjectOnlyService {
        boolean equals(@Nullable Object value);

        int hashCode();

        @NotNull
        String toString();
    }

    /** Mixes an abstract operation with an unsupported default method. */
    public interface DefaultService {
        @NotNull
        String echo(@NotNull String value);

        @NotNull
        default String local(@NotNull String value) {
            return value;
        }
    }

    public interface VarargsService {
        @NotNull
        String echo(@Nullable String... values);
    }

    /** Defines an operation without an argument. */
    public interface NoArgumentService {
        @NotNull
        String echo();
    }

    /** Defines an operation with multiple arguments. */
    public interface TwoArgumentService {
        @NotNull
        String echo(@NotNull String first, @NotNull String second);
    }

    public interface PrefixedVarargsService {
        @NotNull
        String echo(@NotNull String prefix, @NotNull String... values);
    }

    public interface ParameterizedArgumentsService {
        @Nullable
        Map<String, Integer> count(@Nullable List<String> values, @Nullable Map<String, List<Integer>> groups, int offset);
    }

    public interface MixedArgumentsService {
        @NotNull
        String echo(@Nullable String first, int second, @Nullable String third);
    }

    public interface BaseArgumentsService {
        @NotNull
        CharSequence echo();

        @NotNull
        CharSequence echo(@NotNull String first, int second);
    }

    public interface OverloadedArgumentsService extends BaseArgumentsService {
        @NotNull
        @Override
        String echo();

        @NotNull
        String echo(@NotNull String first);

        @NotNull
        @Override
        String echo(@NotNull String first, int second);

        @NotNull
        String echo(@NotNull String first, long second);

        @NotNull
        String echo(@NotNull String first, int second, @NotNull String third);
    }

    /** Defines an unresolved generic method. */
    public interface GenericMethodService {
        @NotNull
        <T> T echo(@NotNull T value);
    }

    /** Defines an unsupported future return type. */
    public interface FutureService {
        @NotNull
        Future<String> echo(@NotNull String value);
    }

    /** Defines an unsupported completion stage return type. */
    public interface StageService {
        @NotNull
        CompletionStage<String> echo(@NotNull String value);
    }

    /** Defines an unsupported concrete asynchronous return type. */
    public interface CompletableService {
        @NotNull
        CompletableFuture<String> echo(@NotNull String value);
    }

    /** Mixes a valid operation with an ignored static method. */
    public interface StaticService {
        @NotNull
        String echo(@NotNull String value);

        static void ignored() {
        }
    }

    /** Preserves concrete parameterized operation types. */
    public interface ParameterizedService {
        @NotNull
        List<String> echo(@NotNull List<String> value);
    }

    /** Defines a parameterized return with a concrete wildcard bound. */
    public interface BaseNumberService {
        @NotNull
        List<? extends Number> numbers(@NotNull String value);
    }

    /** Narrows an inherited parameterized return to integers. */
    public interface NumberService extends BaseNumberService {
        @NotNull
        @Override
        List<Integer> numbers(@NotNull String value);
    }

    /** Defines a counter with a concrete parameterized argument. */
    public interface TypedListCounter {
        int count(@NotNull List<String> values);
    }

    /** Defines a counter with a raw list argument. */
    @SuppressWarnings("rawtypes")
    public interface RawListCounter {
        int count(@NotNull List values);
    }

    /** Inherits unrelated counter methods with unequal generic parameter types. */
    public interface AmbiguousListCounter extends TypedListCounter, RawListCounter {
    }

    /** Resolves inherited counter signatures by explicitly redeclaring the raw argument. */
    @SuppressWarnings("rawtypes")
    public interface ResolvedListCounter extends AmbiguousListCounter {
        @Override
        int count(@NotNull List values);
    }

    /** Defines a loader with a wildcard list result. */
    public interface WideListLoader {
        @NotNull
        List<?> load(@NotNull String value);
    }

    /** Defines a loader with a concrete parameterized result. */
    public interface NarrowListLoader {
        @NotNull
        List<String> load(@NotNull String value);
    }

    /** Inherits unrelated loader methods with unequal generic return types. */
    public interface AmbiguousListLoader extends WideListLoader, NarrowListLoader {
    }

    /** Resolves inherited loader results by explicitly redeclaring the concrete type. */
    public interface ResolvedListLoader extends AmbiguousListLoader {
        @NotNull
        @Override
        List<String> load(@NotNull String value);
    }

    /** Defines a loader with a uniquely narrower raw return class. */
    public interface ArrayListLoader {
        @NotNull
        ArrayList<String> load(@NotNull String value);
    }

    /** Orders the wildcard and typed list parents before the array list parent. */
    public interface WideNarrowArrayLoader extends WideListLoader, NarrowListLoader, ArrayListLoader {
    }

    /** Orders the wildcard parent before the array list and typed list parents. */
    public interface WideArrayNarrowLoader extends WideListLoader, ArrayListLoader, NarrowListLoader {
    }

    /** Orders the typed and wildcard list parents before the array list parent. */
    public interface NarrowWideArrayLoader extends NarrowListLoader, WideListLoader, ArrayListLoader {
    }

    /** Orders the typed parent before the array list and wildcard list parents. */
    public interface NarrowArrayWideLoader extends NarrowListLoader, ArrayListLoader, WideListLoader {
    }

    /** Orders the array list parent before the wildcard and typed list parents. */
    public interface ArrayWideNarrowLoader extends ArrayListLoader, WideListLoader, NarrowListLoader {
    }

    /** Orders the array list parent before the typed and wildcard list parents. */
    public interface ArrayNarrowWideLoader extends ArrayListLoader, NarrowListLoader, WideListLoader {
    }
}
