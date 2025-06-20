/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.krpc.internal

import kotlinx.rpc.internal.utils.InternalRpcApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.reflect.KClass

/**
 * Better unique value holder than ordinal. Easier for testing and maintaining.
 */
@InternalRpcApi
public interface RpcInternalIndexedEnum {
    /**
     * Values between 0 and 65500 are allowed, other are reserved for testing.
     */
    public val uniqueIndex: Int
}

@InternalRpcApi
public abstract class RpcInternalShortEnumKSerializer<E>(
    kClass: KClass<E>,
    public val unknownValue: E,
    public val allValues: Iterable<E>,
) : KSerializer<E> where E : Enum<E>, E : RpcInternalIndexedEnum {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(
        serialName = "ShortEnumKSerializer<${kClass.simpleName}>",
        kind = PrimitiveKind.SHORT, // short can hold 65536 entries, should be more than enough
    )

    override fun deserialize(decoder: Decoder): E {
        val value = decoder.decodeShort() + MODULO
        return allValues.singleOrNull { it.uniqueIndex == value } ?: unknownValue
    }

    override fun serialize(encoder: Encoder, value: E) {
        val shortValue = (value.uniqueIndex - MODULO).toShort()
        encoder.encodeShort(shortValue)
    }

    public companion object {
        public const val MODULO: Int = 32768
    }
}
