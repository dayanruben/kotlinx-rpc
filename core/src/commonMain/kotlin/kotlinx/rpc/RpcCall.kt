/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc

import kotlinx.rpc.descriptor.RpcServiceDescriptor

/**
 * Represents a method call from an RPC service.
 *
 * @property descriptor [RpcServiceDescriptor] of a service that made the call.
 * @property callableName The name of the method being called.
 * @property parameters array of parameters for the call
 * @property serviceId The id of the service that made the call.
 */
public class RpcCall(
    public val descriptor: RpcServiceDescriptor<*>,
    public val callableName: String,
    public val parameters: Array<Any?>,
    public val serviceId: Long,
)
