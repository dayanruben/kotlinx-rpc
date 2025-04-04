/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.krpc.test

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable

@Serializable
data class PayloadWithStream(val payload: String, val stream: @Contextual Flow<String>)

@Serializable
data class PayloadWithPayload(val payload: String, val flow: @Contextual Flow<PayloadWithStream>) {
    suspend fun collect(): List<List<String>> {
        return flow.map {
            it.stream.toList()
        }.toList()
    }
}

fun payload(index: Int = 0): PayloadWithStream {
    return PayloadWithStream(
        "test$index",
        flow { emit("a$index"); emit("b$index"); emit("c$index"); }
    )
}

fun payloadWithPayload(index: Int = 10): PayloadWithPayload {
    return PayloadWithPayload("test$index", payloadStream(index))
}

fun payloadStream(count: Int = 10): Flow<PayloadWithStream> {
    return flow {
        repeat(count) {
            emit(payload(it))
        }
    }
}

fun payloadWithPayloadStream(count: Int = 10): Flow<PayloadWithPayload> {
    return flow {
        repeat(count) {
            emit(payloadWithPayload(it))
        }
    }
}

fun <T> plainFlow(count: Int = 5, get: (Int) -> T): Flow<T> {
    return flow { repeat(count) { emit(get(it)) } }
}

private fun <T, FlowT : MutableSharedFlow<T>> CoroutineScope.runSharedFlow(
    flow: FlowT,
    count: Int = KrpcTestServiceBackend.SHARED_FLOW_REPLAY,
    getter: (Int) -> T,
) = apply {
    launch {
        repeat(count) {
            flow.emit(getter(it))
        }
    }
}

fun <T> CoroutineScope.sharedFlowOfT(getter: (Int) -> T): MutableSharedFlow<T> {
    return MutableSharedFlow<T>(KrpcTestServiceBackend.SHARED_FLOW_REPLAY).also { flow ->
        runSharedFlow(flow) { getter(it) }
    }
}

fun <T> stateFlowOfT(getter: (Int) -> T): MutableStateFlow<T> {
    return MutableStateFlow(getter(-1))
}
