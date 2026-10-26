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

import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.WildcardType;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Future;

final class MessagingRpcContract<T> {
    private static final Map<Class<?>, Class<?>> BOXED = Map.of(
        boolean.class, Boolean.class, byte.class, Byte.class, short.class, Short.class, char.class, Character.class,
        int.class, Integer.class, long.class, Long.class, float.class, Float.class, double.class, Double.class
    );

    private final Map<String, Method> operations;

    private MessagingRpcContract(@NotNull Map<String, Method> operations) {
        this.operations = Map.copyOf(operations);
    }

    @NotNull
    static <T> MessagingRpcContract<T> create(@NotNull Class<T> type) {
        Objects.requireNonNull(type);
        if (!type.isInterface() || !Modifier.isPublic(type.getModifiers()) || type.isSealed() || type.getTypeParameters().length != 0) {
            throw new IllegalArgumentException("RPC contract must be a public, nonsealed interface with concrete types");
        }
        Map<String, List<Method>> declarations = new HashMap<>();
        for (Method method : type.getMethods()) {
            if (Modifier.isStatic(method.getModifiers()) || method.isBridge() || method.isSynthetic() || isObjectMethod(method)) {
                continue;
            }
            validate(method);
            declarations.computeIfAbsent(operationId(method), ignored -> new ArrayList<>()).add(method);
        }
        if (declarations.isEmpty()) {
            throw new IllegalArgumentException("RPC contract must declare at least one operation");
        }
        Map<String, Method> operations = new HashMap<>();
        declarations.forEach((id, methods) -> operations.put(id, selectOperation(methods)));
        for (Method method : type.getMethods()) {
            if (method.isBridge() && method.getParameterCount() == 1 && !operations.containsKey(operationId(method))) {
                throw new IllegalArgumentException("RPC contract has an unresolved generic bridge operation");
            }
        }
        return new MessagingRpcContract<>(operations);
    }

    void requireAccessible(@NotNull Object implementation) {
        for (Method method : operations.values()) {
            if (!method.canAccess(implementation) && !method.trySetAccessible()) {
                throw new IllegalArgumentException("RPC contract operation is not accessible");
            }
        }
    }

    @Nullable
    Method operation(@NotNull String id) {
        return operations.get(id);
    }

    @NotNull
    static String operationId(@NotNull Method method) {
        return method.getName() + "(" + method.getParameterTypes()[0].getTypeName() + ")";
    }

    static boolean accepts(@NotNull Class<?> type, @Nullable Object value) {
        return value == null ? !type.isPrimitive() : BOXED.getOrDefault(type, type).isInstance(value);
    }

    static boolean isObjectMethod(@NotNull Method method) {
        return switch (method.getName()) {
            case "equals" -> Arrays.equals(method.getParameterTypes(), new Class<?>[] {Object.class});
            case "hashCode", "toString" -> method.getParameterCount() == 0;
            default -> false;
        };
    }

    @NotNull
    private static Method selectOperation(@NotNull List<Method> declarations) {
        List<Method> effective = declarations.stream().filter(method -> declarations.stream().noneMatch(other ->
            method.getDeclaringClass() != other.getDeclaringClass()
                && method.getDeclaringClass().isAssignableFrom(other.getDeclaringClass()))).toList();
        Method selected = effective.getFirst();
        for (Method method : effective) {
            if (selected.getReturnType() != method.getReturnType() && selected.getReturnType().isAssignableFrom(method.getReturnType())
                || selected.getReturnType() == method.getReturnType() && !Modifier.isPublic(selected.getDeclaringClass().getModifiers())
                    && Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
                selected = method;
            }
        }
        for (Method method : effective) {
            if (!Arrays.equals(selected.getGenericParameterTypes(), method.getGenericParameterTypes())
                || !method.getReturnType().isAssignableFrom(selected.getReturnType())
                || selected.getReturnType() == method.getReturnType()
                    && !selected.getGenericReturnType().equals(method.getGenericReturnType())) {
                throw new IllegalArgumentException("RPC contract must explicitly redeclare operations with ambiguous generic types");
            }
        }
        return selected;
    }

    private static void validate(@NotNull Method method) {
        if (method.isDefault() || method.isVarArgs() || method.getParameterCount() != 1 || method.getTypeParameters().length != 0
            || !isConcrete(method.getGenericParameterTypes()[0]) || !isConcrete(method.getGenericReturnType())
            || CompletionStage.class.isAssignableFrom(method.getReturnType()) || Future.class.isAssignableFrom(method.getReturnType())
            || operationId(method).length() > 1024) {
            throw new IllegalArgumentException("RPC operations must be abstract, synchronous methods with one concretely typed argument");
        }
    }

    private static boolean isConcrete(@NotNull Type type) {
        if (type instanceof Class<?>) {
            return true;
        }
        if (type instanceof ParameterizedType parameterized) {
            return (parameterized.getOwnerType() == null || isConcrete(parameterized.getOwnerType()))
                && Arrays.stream(parameterized.getActualTypeArguments()).allMatch(MessagingRpcContract::isConcrete);
        }
        if (type instanceof GenericArrayType array) {
            return isConcrete(array.getGenericComponentType());
        }
        if (type instanceof WildcardType wildcard) {
            return Arrays.stream(wildcard.getUpperBounds()).allMatch(MessagingRpcContract::isConcrete)
                && Arrays.stream(wildcard.getLowerBounds()).allMatch(MessagingRpcContract::isConcrete);
        }
        return false;
    }
}
