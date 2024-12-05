/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalRpcApi::class)

import kotlin.coroutines.*
import kotlinx.coroutines.*
import kotlinx.rpc.annotations.Rpc
import kotlinx.rpc.withService
import kotlinx.rpc.RpcClient
import kotlinx.rpc.RpcServer
import kotlinx.rpc.registerService
import kotlinx.rpc.descriptor.serviceDescriptorOf
import kotlinx.rpc.awaitFieldInitialization
import kotlinx.rpc.internal.utils.ExperimentalRpcApi

@Rpc
interface MyService

class NotAService(val coroutineContext: CoroutineContext)

class MyServiceImpl(override val coroutineContext: CoroutineContext) : MyService

inline suspend fun <@Rpc reified T : Any> ok(client: RpcClient, server: RpcServer, impl: T, myServiceImpl: MyService) {
    client.withService<MyService>()
    client.withService<T>()

    server.registerService<MyService> { MyServiceImpl(it) }
    server.registerService<T> { impl }

    myServiceImpl.awaitFieldInitialization<MyService>()
    myServiceImpl.awaitFieldInitialization<MyService, Int> { 1 }

    impl.awaitFieldInitialization<T>()
    impl.awaitFieldInitialization<T, Int> { 1 }

    serviceDescriptorOf<MyService>()
    serviceDescriptorOf<T>()
}

inline suspend fun <reified T : Any> fail(client: RpcClient, server: RpcServer, impl: T, myServiceImpl: MyServiceImpl, notAServiceImpl: NotAService) {
    client.withService<<!CHECKED_ANNOTATION_VIOLATION!>MyServiceImpl<!>>()
    client.withService<<!CHECKED_ANNOTATION_VIOLATION!>NotAService<!>>()
    client.withService<<!CHECKED_ANNOTATION_VIOLATION!>T<!>>()

    server.registerService<<!CHECKED_ANNOTATION_VIOLATION!>MyServiceImpl<!>> { MyServiceImpl(it) }
    server.registerService<<!CHECKED_ANNOTATION_VIOLATION!>NotAService<!>> { NotAService(it) }
    server.registerService<<!CHECKED_ANNOTATION_VIOLATION!>T<!>> { impl }

    myServiceImpl.awaitFieldInitialization<<!CHECKED_ANNOTATION_VIOLATION!>MyServiceImpl<!>>()
    myServiceImpl.awaitFieldInitialization<<!CHECKED_ANNOTATION_VIOLATION!>MyServiceImpl<!>, Int> { 1 }

    notAServiceImpl.awaitFieldInitialization<<!CHECKED_ANNOTATION_VIOLATION!>NotAService<!>>()
    notAServiceImpl.awaitFieldInitialization<<!CHECKED_ANNOTATION_VIOLATION!>NotAService<!>, Int> { 1 }

    impl.awaitFieldInitialization<<!CHECKED_ANNOTATION_VIOLATION!>T<!>>()
    impl.awaitFieldInitialization<<!CHECKED_ANNOTATION_VIOLATION!>T<!>, Int> { 1 }

    serviceDescriptorOf<<!CHECKED_ANNOTATION_VIOLATION!>MyServiceImpl<!>>()
    serviceDescriptorOf<<!CHECKED_ANNOTATION_VIOLATION!>NotAService<!>>()
    serviceDescriptorOf<<!CHECKED_ANNOTATION_VIOLATION!>T<!>>()
}