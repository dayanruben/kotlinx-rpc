/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.codegen.extension

import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrProperty
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.declarations.IrValueParameter
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.defaultType
import org.jetbrains.kotlin.ir.util.kotlinFqName

class ServiceDeclaration(
    val service: IrClass,
    val stubClass: IrClass,
    val methods: List<Method>,
) {
    val fqName = service.kotlinFqName.asString()

    val serviceType = service.defaultType

    sealed interface Callable {
        val name: String
    }

    class Method(
        val function: IrSimpleFunction,
        val arguments: List<Argument>,
    ) : Callable {
        override val name: String = function.name.asString()

        class Argument(
            val value: IrValueParameter,
            val type: IrType,
            val isOptional: Boolean,
        )
    }
}
