/*
 * Copyright 2023-2024 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.codegen

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.ir.builders.declarations.IrFieldBuilder
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.platform.TargetPlatform

interface VersionSpecificApi {
    fun isJs(platform: TargetPlatform?): Boolean
    fun isWasm(platform: TargetPlatform?): Boolean

    fun referenceClass(context: IrPluginContext, packageName: String, name: String): IrClassSymbol?

    fun referenceFunctions(
        context: IrPluginContext,
        packageName: String,
        name: String,
    ): Collection<IrSimpleFunctionSymbol>

    fun IrValueParameter.copyToVS(
        irFunction: IrFunction,
        origin: IrDeclarationOrigin = this.origin,
    ): IrValueParameter

    fun IrSimpleFunction.addExtensionReceiverVS(
        type: IrType,
        origin: IrDeclarationOrigin = IrDeclarationOrigin.DEFINED,
    ): IrValueParameter

    var IrFieldBuilder.isFinalVS: Boolean

    var IrCall.originVS: IrStatementOrigin?

    var IrConstructor.isPrimaryVS: Boolean

    val messageCollectorKey: CompilerConfigurationKey<MessageCollector>

    companion object {
        lateinit var INSTANCE: VersionSpecificApi
    }
}

@Suppress("unused")
fun undefinedAPI(): Nothing = error("This API is not defined in current Kotlin version")
