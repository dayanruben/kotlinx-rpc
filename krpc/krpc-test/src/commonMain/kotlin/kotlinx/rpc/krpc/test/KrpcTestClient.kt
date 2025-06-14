/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.krpc.test

import kotlinx.rpc.krpc.KrpcConfig
import kotlinx.rpc.krpc.KrpcTransport
import kotlinx.rpc.krpc.client.InitializedKrpcClient
import kotlinx.rpc.krpc.client.KrpcClient

/**
 * Implementation of [KrpcClient] that can be used to test custom [KrpcTransport].
 *
 * NOTE: one [KrpcTransport] is meant to be used by only one client,
 * but this class allows for abuse. Be cautious about how you handle [KrpcTransport] with this class.
 */
class KrpcTestClient(
    config: KrpcConfig.Client,
    transport: KrpcTransport,
) : InitializedKrpcClient(config, transport)
