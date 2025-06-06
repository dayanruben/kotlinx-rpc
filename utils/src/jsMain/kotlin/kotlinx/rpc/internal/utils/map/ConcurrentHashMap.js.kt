/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.internal.utils.map

import kotlinx.rpc.internal.utils.InternalRpcApi

@InternalRpcApi
public actual fun <K : Any, V : Any> RpcInternalConcurrentHashMap(
    initialSize: Int,
): RpcInternalConcurrentHashMap<K, V> {
    return SynchronizedHashMap()
}
