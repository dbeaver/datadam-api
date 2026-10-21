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

import org.jkiss.code.NotNull;
import org.jkiss.code.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(10)
public class RpcContractTest extends RpcTestSupport {
    @Test
    void rejectsInvalidContractsBeforeSendingAndLeavesTheTopicAvailable() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, codec, OPTIONS)) {
            for (Class<?> type : List.of(
                String.class, HiddenService.class, SealedService.class, GenericService.class, RawGenericService.class,
                EmptyService.class, StaticOnlyService.class, ObjectOnlyService.class,
                DefaultService.class, VarargsService.class, NoArgumentService.class, TwoArgumentService.class,
                GenericMethodService.class, FutureService.class, StageService.class, CompletableService.class
            )) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("service", type), type.getName());
                assertThrows(IllegalArgumentException.class, () -> endpoint.register("service", type, null), type.getName());
            }
            assertThrows(IllegalArgumentException.class, () -> endpoint.register("service", DefaultService.class, value -> value));
            try (var registration = endpoint.register("service", StaticService.class, value -> value)) {
                assertTrue(transport.sent.isEmpty());
                assertTrue(codec.encoded.isEmpty());
                assertTrue(codec.decoded.isEmpty());
            }
        }
    }

    @Test
    void rejectsUnresolvedTypeVariablesInParameterizedOwnerTypes() {
        assertThrows(IllegalArgumentException.class, () -> RpcContract.create(RawOwnedGenericService.class));
    }

    @Test
    void rejectsGenericParentBridgesWhoseErasedArgumentHasNoConcreteOperation() throws Exception {
        var bridge = GenericBridgeChild.class.getDeclaredMethod("echo", Object.class);
        assertTrue(bridge.isBridge());
        assertTrue(bridge.isSynthetic());
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, codec, OPTIONS)) {
            assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("bridge", GenericBridgeChild.class));
            assertThrows(IllegalArgumentException.class,
                () -> endpoint.register("bridge", GenericBridgeChild.class, value -> value));
            assertTrue(codec.encoded.isEmpty());
            assertTrue(codec.decoded.isEmpty());
            assertTrue(transport.sent.isEmpty());
        }
    }

    @Test
    void rejectsUnrelatedParentsWithAmbiguousGenericParameterOrReturnTypesBeforeSending() throws Exception {
        var transport = new MemoryTransport();
        var codec = new TestCodec();
        try (var endpoint = RpcEndpoint.open(transport, codec, OPTIONS)) {
            for (Class<?> type : List.of(AmbiguousListCounter.class, AmbiguousListLoader.class)) {
                assertThrows(IllegalArgumentException.class, () -> endpoint.proxy("ambiguous", type), type.getName());
                assertThrows(IllegalArgumentException.class, () -> endpoint.register("ambiguous", type, null), type.getName());
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
            var operation = RpcContract.create(type).operation("load(java.lang.String)");

            assertNotNull(operation, type.getName());
            assertEquals(ArrayList.class, operation.getReturnType(), type.getName());
            assertEquals(expectedType, operation.getGenericReturnType(), type.getName());
        }
    }

    @Test
    void explicitChildRedeclarationsResolveGenericTypesForCallsThroughParentAndChildReferences() throws Exception {
        var count = ResolvedListCounter.class.getDeclaredMethod("count", List.class);
        var load = ResolvedListLoader.class.getDeclaredMethod("load", String.class);
        assertEquals(count, RpcContract.create(ResolvedListCounter.class).operation("count(java.util.List)"));
        assertEquals(load, RpcContract.create(ResolvedListLoader.class).operation("load(java.lang.String)"));
        var clientTransport = new MemoryTransport();
        var serverTransport = new MemoryTransport();
        var clientCodec = new TestCodec();
        var serverCodec = new TestCodec();
        connect(clientTransport, serverTransport);
        try (var client = RpcEndpoint.open(clientTransport, clientCodec, OPTIONS);
             var server = RpcEndpoint.open(serverTransport, serverCodec, OPTIONS);
             var counter = server.register("count", ResolvedListCounter.class, List::size);
             var loader = server.register("load", ResolvedListLoader.class, List::of)) {
            ResolvedListCounter childCounter = client.proxy("count", ResolvedListCounter.class);
            TypedListCounter typedCounter = childCounter;
            assertEquals(2, typedCounter.count(List.of("one", "two")));
            assertEquals(1, childCounter.count(List.of("child")));

            ResolvedListLoader childLoader = client.proxy("load", ResolvedListLoader.class);
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
        RpcContract<StaticService> contract = RpcContract.create(StaticService.class);
        assertEquals(StaticService.class.getMethod("echo", String.class), contract.operation("echo(java.lang.String)"));
        assertNull(contract.operation("ignored()"));

        RpcContract<ParameterizedService> parameterized = RpcContract.create(ParameterizedService.class);
        var method = ParameterizedService.class.getMethod("echo", List.class);
        assertEquals(method, parameterized.operation("echo(java.util.List)"));
        assertEquals(method.getGenericParameterTypes()[0], parameterized.operation("echo(java.util.List)").getGenericParameterTypes()[0]);
        assertEquals(method.getGenericReturnType(), parameterized.operation("echo(java.util.List)").getGenericReturnType());
    }

    @Test
    void prefersTheConcreteParameterizedReturnRedeclaredByAChildContract() throws Exception {
        RpcContract<NumberService> contract = RpcContract.create(NumberService.class);

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

    /** Defines an unsupported variable argument operation. */
    public interface VarargsService {
        @NotNull
        String echo(@NotNull String... values);
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
