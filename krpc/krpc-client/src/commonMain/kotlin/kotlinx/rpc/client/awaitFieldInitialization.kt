/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.client

import kotlinx.rpc.RPC
import kotlinx.rpc.internal.RPCDeferredField
import kotlinx.rpc.internal.RPCServiceFieldsProvider
import kotlinx.rpc.internal.findRPCProviderInCompanion
import kotlin.reflect.KClass

/**
 * Waits for the initialization of an RPC field in the generated client:
 *
 * ```kotlin
 * interface MyService : RPC {
 *     val stateFlow: StateFlow<Int>
 * }
 *
 * val service = rpcClient.withService<MyService>()
 * val currentValue = service.awaitFieldInitialization { stateFlow }.value
 * ```
 *
 * @param T service type
 * @param R field type
 * @param getter function that returns the field of the context service to wait for.
 * @return service filed after it was initialized.
 */
public suspend fun <T : RPC, R> T.awaitFieldInitialization(getter: T.() -> R): R {
    val field = getter()

    if (field is RPCDeferredField<*>) {
        @Suppress("UNCHECKED_CAST")
        return (field as RPCDeferredField<R>).await()
    }

    error("Please choose required field for a valid RPC client generated by RPCClient.withService method")
}

/**
 * Waits for the initialization of all RPC fields in the generated client:
 *
 * ```kotlin
 * interface MyService : RPC {
 *     val stateFlow1: StateFlow<Int>
 *     val stateFlow2: StateFlow<Int>
 * }
 *
 * val service = rpcClient.withService<MyService>()
 * val currentValue = service.awaitFieldInitialization()
 * // fields `stateFlow1` and `stateFlow2` are initialized
 * ```
 *
 * @param T service type
 * @return specified service, after all of it's field were initialized.
 */
public suspend inline fun <reified T : RPC> T.awaitFieldInitialization(): T {
    return awaitFieldInitialization(T::class)
}

/**
 * Waits for the initialization of all RPC fields in the generated client:
 *
 * ```kotlin
 * interface MyService : RPC {
 *     val stateFlow1: StateFlow<Int>
 *     val stateFlow2: StateFlow<Int>
 * }
 *
 * val service = rpcClient.withService<MyService>()
 * val currentValue = service.awaitFieldInitialization(MyService::class)
 * // fields `stateFlow1` and `stateFlow2` are initialized
 * ```
 *
 * @param T service type
 * @param kClass [KClass] of the [T] type.
 * @return specified service, after all of it's field were initialized.
 */
public suspend fun <T : RPC> T.awaitFieldInitialization(kClass: KClass<T>): T {
    findRPCProviderInCompanion<RPCServiceFieldsProvider<T>>(kClass)
        .rpcFields(this)
        .forEach { field ->
            field.await()
        }

    return this
}