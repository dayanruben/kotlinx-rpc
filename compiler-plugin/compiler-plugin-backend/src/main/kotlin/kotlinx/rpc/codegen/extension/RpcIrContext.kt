/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.codegen.extension

import kotlinx.rpc.codegen.VersionSpecificApi
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.ir.declarations.IrSimpleFunction
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrPropertySymbol
import org.jetbrains.kotlin.ir.symbols.IrSimpleFunctionSymbol
import org.jetbrains.kotlin.ir.types.makeNullable
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.functions
import org.jetbrains.kotlin.ir.util.isVararg
import org.jetbrains.kotlin.ir.util.nestedClasses
import org.jetbrains.kotlin.ir.util.properties
import org.jetbrains.kotlin.platform.konan.isNative
import org.jetbrains.kotlin.types.Variance

internal class RpcIrContext(
    val pluginContext: IrPluginContext,
    val versionSpecificApi: VersionSpecificApi,
) {
    val irBuiltIns = pluginContext.irBuiltIns

    val anyNullable by lazy {
        irBuiltIns.anyType.makeNullable()
    }

    val arrayOfAnyNullable by lazy {
        irBuiltIns.arrayClass.typeWith(anyNullable, Variance.OUT_VARIANCE)
    }

    val listOfAnnotations by lazy {
        irBuiltIns.listClass.typeWith(irBuiltIns.annotationType)
    }

    val arrayOfAnnotations by lazy {
        irBuiltIns.arrayClass.typeWith(irBuiltIns.annotationType, Variance.OUT_VARIANCE)
    }

    val kTypeClass by lazy {
        getIrClassSymbol("kotlin.reflect", "KType")
    }

    val suspendFunction2 by lazy {
        getIrClassSymbol("kotlin.coroutines", "SuspendFunction2")
    }

    val flow by lazy {
        getIrClassSymbol("kotlinx.coroutines.flow", "Flow")
    }

    val pair by lazy {
        getIrClassSymbol("kotlin", "Pair")
    }

    val rpcClient by lazy {
        getRpcIrClassSymbol("RpcClient")
    }

    val rpcCall by lazy {
        getRpcIrClassSymbol("RpcCall")
    }

    val withServiceDescriptor by lazy {
        getRpcIrClassSymbol("WithServiceDescriptor", "internal")
    }

    val rpcServiceDescriptor by lazy {
        getRpcIrClassSymbol("RpcServiceDescriptor", "descriptor")
    }

    val rpcType by lazy {
        getRpcIrClassSymbol("RpcType", "descriptor")
    }

    val rpcTypeDefault by lazy {
        getRpcIrClassSymbol("RpcTypeDefault", "descriptor")
    }

    val rpcTypeKrpc by lazy {
        getRpcIrClassSymbol("RpcTypeKrpc", "descriptor")
    }

    val rpcCallable by lazy {
        getRpcIrClassSymbol("RpcCallable", "descriptor")
    }

    val rpcCallableDefault by lazy {
        getRpcIrClassSymbol("RpcCallableDefault", "descriptor")
    }

    private val rpcInvokator by lazy {
        getRpcIrClassSymbol("RpcInvokator", "descriptor")
    }

    val rpcInvokatorMethod by lazy {
        rpcInvokator.subClass("Method")
    }

    val rpcParameter by lazy {
        getRpcIrClassSymbol("RpcParameter", "descriptor")
    }

    val rpcParameterDefault by lazy {
        getRpcIrClassSymbol("RpcParameterDefault", "descriptor")
    }

    val kSerializer by lazy {
        getIrClassSymbol("kotlinx.serialization", "KSerializer")
    }

    val kSerializerAnyNullable by lazy {
        kSerializer.typeWith(anyNullable)
    }

    val kSerializerAnyNullableKClass by lazy {
        irBuiltIns.kClassClass.typeWith(kSerializerAnyNullable)
    }

    fun isJsTarget(): Boolean {
        return versionSpecificApi.isJs(pluginContext.platform)
    }

    fun isNativeTarget(): Boolean {
        return pluginContext.platform.isNative()
    }

    fun isWasmTarget(): Boolean {
        return versionSpecificApi.isWasm(pluginContext.platform)
    }

    val functions = Functions()

    inner class Functions {
        val rpcClientCall by lazy {
            rpcClient.namedFunction("call")
        }

        val rpcClientCallServerStreaming by lazy {
            rpcClient.namedFunction("callServerStreaming")
        }

        val typeOf by lazy {
            namedFunction("kotlin.reflect", "typeOf")
        }

        val emptyArray by lazy {
            namedFunction("kotlin", "emptyArray")
        }

        val mapOf by lazy {
            namedFunction("kotlin.collections", "mapOf") {
                vsApi {
                    it.owner.valueParametersVS().singleOrNull()?.isVararg ?: false
                }
            }
        }

        val emptyList by lazy {
            namedFunction("kotlin.collections", "emptyList")
        }

        val listOf by lazy {
            namedFunction("kotlin.collections", "listOf") {
                vsApi {
                    it.owner.valueParametersVS().singleOrNull()?.isVararg ?: false
                }
            }
        }

        val mapGet by lazy {
            irBuiltIns.mapClass.namedFunction("get")
        }

        val arrayGet by lazy {
            irBuiltIns.arrayClass.namedFunction("get")
        }

        val emptyMap by lazy {
            namedFunction("kotlin.collections", "emptyMap")
        }

        val to by lazy {
            namedFunction("kotlin", "to")
        }

        private fun IrClassSymbol.namedFunction(name: String): IrSimpleFunction {
            return owner.functions.single { it.name.asString() == name }
        }

        private fun namedFunction(
            packageName: String,
            name: String,
            filterOverloads: ((IrSimpleFunctionSymbol) -> Boolean)? = null,
        ): IrSimpleFunctionSymbol {
            val found = versionSpecificApi.referenceFunctions(pluginContext, packageName, name)

            return if (filterOverloads == null) found.first() else found.first(filterOverloads)
        }
    }

    val properties = Properties()

    inner class Properties {
        val rpcServiceDescriptorFqName by lazy {
            rpcServiceDescriptor.namedProperty("fqName")
        }

        private fun IrClassSymbol.namedProperty(name: String): IrPropertySymbol {
            return owner.properties.single { it.name.asString() == name }.symbol
        }
    }

    private fun IrClassSymbol.subClass(name: String): IrClassSymbol {
        return owner.nestedClasses.single { it.name.asString() == name }.symbol
    }

    private fun getRpcIrClassSymbol(name: String, subpackage: String? = null): IrClassSymbol {
        val suffix = subpackage?.let { ".$subpackage" } ?: ""
        return getIrClassSymbol("kotlinx.rpc$suffix", name)
    }

    private fun getIrClassSymbol(packageName: String, name: String): IrClassSymbol {
        return versionSpecificApi.referenceClass(pluginContext, packageName, name)
            ?: error("Unable to find symbol. Package: $packageName, name: $name")
    }

    private fun <T> vsApi(body: VersionSpecificApi.() -> T): T {
        return versionSpecificApi.run(body)
    }
}
