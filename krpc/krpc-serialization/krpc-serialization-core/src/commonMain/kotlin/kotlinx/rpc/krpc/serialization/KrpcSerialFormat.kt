/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.krpc.serialization

import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.SerialFormat
import kotlinx.serialization.StringFormat
import kotlinx.serialization.modules.SerializersModule

/**
 * [KrpcSerialFormat] interface defines an object which helps kRPC protocol to work with various serialization formats
 *
 * Each serialization that can be used with kRPC protocol should define an object of [KrpcSerialFormat]
 */
public interface KrpcSerialFormat<Format : SerialFormat, FormatBuilder : Any> {
    /**
     * Generalization of kotlinx.serialization approach to configure serial formats
     *
     * @param from base instance of [Format] that should be extended via [builderConsumer].
     *             If not provided default one should be used
     * @param builderConsumer function that configures serial format
     */
    public fun withBuilder(from: Format? = null, builderConsumer: FormatBuilder.() -> Unit): Format

    /**
     * Special extension for [SerialFormat] API, that allows to extend it with additional [SerializersModule].
     * Required for kRPC protocol properly to work properly with special argument types like Flows
     */
    public fun FormatBuilder.applySerializersModule(serializersModule: SerializersModule)
}

/**
 * Special wrapper class that is used to register serialization format in [KrpcSerialFormatConfiguration]
 * Comes in two instances: [KrpcSerialFormatBuilder.Binary] and [KrpcSerialFormatBuilder.String]
 *
 * @param Format [SerialFormat] type that this builder should create for RPC
 * @param FormatBuilder builder class for [Format]
 * @param rpcSerialFormat - instance of [KrpcSerialFormat]
 * @param from - optional default format instance
 * @param builder - builder function for format configuration
 */
public sealed class KrpcSerialFormatBuilder<Format : SerialFormat, FormatBuilder : Any>(
    rpcSerialFormat: KrpcSerialFormat<Format, FormatBuilder>,
    from: Format? = null,
    builder: FormatBuilder.() -> Unit,
) {
    private val builder: (SerializersModule?) -> SerialFormat = { serializersModule ->
        with(rpcSerialFormat) {
            withBuilder(from) {
                builder()
                serializersModule?.let { applySerializersModule(it) }
            }
        }
    }

    @InternalRpcApi
    public fun build(): SerialFormat = builder(null)

    @InternalRpcApi
    public fun applySerializersModuleAndBuild(serializersModule: SerializersModule): SerialFormat {
        return builder(serializersModule)
    }

    /**
     * @see KrpcSerialFormatBuilder
     */
    public class Binary<Format : BinaryFormat, FormatBuilder : Any>(
        rpcSerialFormat: KrpcSerialFormat<Format, FormatBuilder>,
        from: Format? = null,
        builder: FormatBuilder.() -> Unit,
    ) : KrpcSerialFormatBuilder<Format, FormatBuilder>(rpcSerialFormat, from, builder)

    /**
     * @see KrpcSerialFormatBuilder
     */
    public class String<Format : StringFormat, FormatBuilder : Any>(
        rpcSerialFormat: KrpcSerialFormat<Format, FormatBuilder>,
        from: Format? = null,
        builder: FormatBuilder.() -> Unit,
    ) : KrpcSerialFormatBuilder<Format, FormatBuilder>(rpcSerialFormat, from, builder)
}
