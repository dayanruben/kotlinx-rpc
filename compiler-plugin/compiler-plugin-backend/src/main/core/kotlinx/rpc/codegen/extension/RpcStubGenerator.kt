/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.codegen.extension

import kotlinx.rpc.codegen.VersionSpecificApi
import kotlinx.rpc.codegen.common.rpcMethodClassName
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.jvm.functionByName
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.descriptors.DescriptorVisibilities
import org.jetbrains.kotlin.descriptors.DescriptorVisibility
import org.jetbrains.kotlin.descriptors.Modality
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.SpecialNames
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.util.OperatorNameConventions
import kotlin.properties.Delegates

private object Stub {
    const val CLIENT = "__rpc_client"
    const val STUB_ID = "__rpc_stub_id"
}

private object Descriptor {
    const val CALLABLE_MAP = "callableMap"
    const val FQ_NAME = "fqName"
    const val GET_CALLABLE = "getCallable"
    const val CREATE_INSTANCE = "createInstance"
}

@Suppress("detekt.LargeClass", "detekt.TooManyFunctions")
internal class RpcStubGenerator(
    private val declaration: ServiceDeclaration,
    private val ctx: RpcIrContext,
    @Suppress("unused")
    private val logger: MessageCollector,
) {
    private fun irBuilder(symbol: IrSymbol): DeclarationIrBuilder =
        DeclarationIrBuilder(ctx.pluginContext, symbol, symbol.owner.startOffset, symbol.owner.endOffset)

    private var stubClass: IrClass by Delegates.notNull()
    private var stubClassThisReceiver: IrValueParameter by Delegates.notNull()

    fun generate() {
        generateStubClass()

        addAssociatedObjectAnnotationIfPossible()
    }

    private fun generateStubClass() {
        declaration.stubClass.apply {
            declarations.removeAll { it !is IrClass } // preserve the companion object and method classes
            annotations = emptyList()

            stubClass = this
            stubClassThisReceiver = thisReceiver
                ?: error("Declared stub class must have thisReceiver: ${stubClass.name.asString()}")

            superTypes = listOf(declaration.serviceType)

            generateStubConstructor()
            generateStubContent()
        }
    }

    private var clientValueParameter: IrValueParameter by Delegates.notNull()
    private var stubIdValueParameter: IrValueParameter by Delegates.notNull()

    /**
     * Constructor of a stub service:
     *
     * ```kotlin
     * class `$rpcServiceStub`(private val __rpc_stub_id: Long, private val __rpc_client: RpcClient) : MyService
     * ```
     */
    private fun IrClass.generateStubConstructor() {
        addConstructor {
            name = this@generateStubConstructor.name
            isPrimary = true
        }.apply {
            stubIdValueParameter = addValueParameter {
                name = Name.identifier(Stub.STUB_ID)
                type = ctx.irBuiltIns.longType
            }

            clientValueParameter = addValueParameter {
                name = Name.identifier(Stub.CLIENT)
                type = ctx.rpcClient.defaultType
            }

            addDefaultConstructor(this)
        }
    }

    private fun IrClass.generateStubContent() {
        generateProperties()

        generateMethods()

        generateCompanionObject()

        addAnyOverrides(declaration.service)
    }

    private fun IrClass.generateProperties() {
        stubIdProperty()

        clientProperty()
    }

    private var stubIdProperty: IrProperty by Delegates.notNull()

    /**
     * __rpc_stub_id property from the constructor
     *
     * ```kotlin
     * class `$rpcServiceStub`(private val __rpc_stub_id: Long, private val __rpc_client: RpcClient) : MyService
     * ```
     */
    private fun IrClass.stubIdProperty() {
        stubIdProperty = addConstructorProperty(Stub.STUB_ID, ctx.irBuiltIns.longType, stubIdValueParameter)
    }

    private var clientProperty: IrProperty by Delegates.notNull()

    /**
     * __rpc_client property from the constructor
     *
     * ```kotlin
     * class `$rpcServiceStub`(private val __rpc_stub_id: Long, private val __rpc_client: RpcClient) : MyService
     * ```
     */
    private fun IrClass.clientProperty() {
        clientProperty = addConstructorProperty(Stub.CLIENT, ctx.rpcClient.defaultType, clientValueParameter)
    }

    private fun IrClass.addConstructorProperty(
        propertyName: String,
        propertyType: IrType,
        valueParameter: IrValueParameter,
        propertyVisibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
    ): IrProperty {
        return addConstructorProperty(Name.identifier(propertyName), propertyType, valueParameter, propertyVisibility)
    }

    private fun IrClass.addConstructorProperty(
        propertyName: Name,
        propertyType: IrType,
        valueParameter: IrValueParameter,
        propertyVisibility: DescriptorVisibility = DescriptorVisibilities.PRIVATE,
    ): IrProperty {
        return addProperty {
            name = propertyName
            visibility = propertyVisibility
        }.apply {
            addBackingFieldUtil {
                visibility = DescriptorVisibilities.PRIVATE
                type = propertyType
                vsApi { isFinalVS = true }
            }.apply {
                initializer = factory.createExpressionBody(
                    IrGetValueImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = valueParameter.type,
                        symbol = valueParameter.symbol,
                        origin = IrStatementOrigin.INITIALIZE_PROPERTY_FROM_PARAMETER,
                    )
                )
            }

            addDefaultGetter(this@addConstructorProperty, ctx.irBuiltIns) {
                visibility = propertyVisibility
            }
        }
    }

    private fun IrClass.generateMethods() {
        declaration.methods.forEach {
            generateRpcMethod(it)
        }
    }

    private val methodClasses = mutableListOf<IrClass>()

    /**
     * RPC Methods generation
     *
     * All method classes MUST be already generated, so this plugin can use them.
     *
     * ```kotlin
     *  final override suspend fun <method-name>(<method-args>): <return-type> {
     *      return scopedClientCall(this) { // this: CoroutineScope
     *          __rpc_client.call(RpcCall(
     *              descriptor = Companion,
     *              callableName = "<method-name>",
     *              data = (<method-class>(<method-args>)|<method-object>),
     *              serviceId = __rpc_stub_id,
     *          ))
     *      }
     *  }
     * ```
     *
     * Where:
     *  - `<method-name>` - the name of the RPC method
     *  - `<method-args>` - array of arguments for the method
     *  - `<return-type>` - the return type of the method
     *  - `<method-class>` or `<method-object>` - generated class or object for this method
     */
    @Suppress(
        "detekt.NestedBlockDepth",
        "detekt.LongMethod",
    )
    private fun IrClass.generateRpcMethod(method: ServiceDeclaration.Method) {
        val isMethodObject = method.arguments.isEmpty()

        val methodClassName = method.function.name.rpcMethodClassName
        val methodClass: IrClass = initiateAndGetMethodClass(methodClassName, method)

        addFunction {
            name = method.function.name
            visibility = method.function.visibility
            returnType = method.function.returnType
            modality = Modality.OPEN

            isSuspend = method.function.isSuspend
        }.apply {
            val functionThisReceiver = vsApi {
                stubClassThisReceiver.copyToVS(this@apply, origin = IrDeclarationOrigin.DEFINED)
            }.also {
                vsApi {
                    dispatchReceiverParameterVS = it
                }
            }

            val arguments = method.arguments.memoryOptimizedMap { arg ->
                addValueParameter {
                    name = arg.value.name
                    type = arg.type
                }
            }

            overriddenSymbols = listOf(method.function.symbol)

            body = irBuilder(symbol).irBlockBody {
                if (method.function.isNonSuspendingWithFlowReturn()) {
                    +irReturn(
                        irRpcMethodClientCall(
                            method = method,
                            functionThisReceiver = functionThisReceiver,
                            isMethodObject = isMethodObject,
                            methodClass = methodClass,
                            arguments = arguments,
                        )
                    )

                    return@irBlockBody
                }

                val call = irRpcMethodClientCall(
                    method = method,
                    functionThisReceiver = functionThisReceiver,
                    isMethodObject = isMethodObject,
                    methodClass = methodClass,
                    arguments = arguments,
                )

                if (method.function.returnType == ctx.irBuiltIns.unitType) {
                    +call
                } else {
                    +irReturn(call)
                }
            }
        }
    }

    /**
     * Frontend plugins generate the following:
     * ```kotlin
     * // Given rpc method:
     * suspend fun hello(arg1: String, arg2: Int)
     *
     * // Frontend generates:
     * @Serializable
     * class hello$rpcMethod {
     *    constructor(arg1: String, arg2: String)
     *
     *    val arg1: String
     *    val arg2: Int
     * }
     * ```
     *
     * This method generates missing getters and backing fields' values.
     * And adds RpcMethodClassArguments supertype with `asArray` method implemented.
     *
     * Resulting class:
     * ```kotlin
     * @Serializable
     * class hello$rpcMethod(
     *    val arg1: String,
     *    val arg2: Int,
     * ) : RpcMethodClassArguments {
     *    // or emptyArray when no arguments
     *    override fun asArray(): Array<Any?> = arrayOf(arg1, arg2)
     * }
     * ```
     */
    private fun IrClass.initiateAndGetMethodClass(methodClassName: Name, method: ServiceDeclaration.Method): IrClass {
        val methodClass = findDeclaration<IrClass> { it.name == methodClassName }
            ?: error(
                "Expected $methodClassName class to be present in stub class " +
                        "${declaration.service.name}${stubClass.name}"
            )

        methodClasses.add(methodClass)

        val methodClassThisReceiver = methodClass.thisReceiver
            ?: error("Expected ${methodClass.name} of ${stubClass.name} to have a thisReceiver")

        val properties = if (methodClass.isClass) {
            val argNames = method.arguments.memoryOptimizedMap { it.value.name }.toSet()

            // remove frontend generated properties
            // new ones will be used instead
            methodClass.declarations.removeAll { it is IrProperty && it.name in argNames }

            // primary constructor, serialization may add another
            val constructor = methodClass.constructors.single {
                vsApi {
                    method.arguments.size == it.valueParametersVS().size
                }
            }

            vsApi { constructor.isPrimaryVS = true }
            methodClass.addDefaultConstructor(constructor)

            vsApi { constructor.valueParametersVS() }.memoryOptimizedMap { valueParam ->
                methodClass.addConstructorProperty(
                    propertyName = valueParam.name,
                    propertyType = valueParam.type,
                    valueParameter = valueParam,
                    propertyVisibility = DescriptorVisibilities.PUBLIC,
                )
            }
        } else {
            emptyList()
        }

        methodClass.superTypes += ctx.rpcMethodClass.defaultType

        methodClass.addFunction {
            name = ctx.functions.asArray.name
            visibility = DescriptorVisibilities.PUBLIC
            returnType = ctx.arrayOfAnyNullable
            modality = Modality.OPEN
        }.apply {
            overriddenSymbols = listOf(ctx.functions.asArray.symbol)

            val asArrayThisReceiver = vsApi {
                methodClassThisReceiver.copyToVS(this@apply, origin = IrDeclarationOrigin.DEFINED)
            }.also {
                vsApi {
                    dispatchReceiverParameterVS = it
                }
            }

            body = irBuilder(symbol).irBlockBody {
                val callee = if (methodClass.isObject) {
                    ctx.functions.emptyArray
                } else {
                    ctx.irBuiltIns.arrayOf
                }

                val arrayOfCall = irCall(callee, type = ctx.arrayOfAnyNullable).apply arrayOfCall@{
                    if (methodClass.isObject) {
                        arguments {
                            types { +ctx.anyNullable }
                        }

                        return@arrayOfCall
                    }

                    val vararg = irVararg(
                        elementType = ctx.anyNullable,
                        values = properties.memoryOptimizedMap { property ->
                            irCallProperty(methodClass, property, symbol = asArrayThisReceiver.symbol)
                        },
                    )

                    arguments {
                        types {
                            +ctx.anyNullable
                        }

                        values {
                            +vararg
                        }
                    }

                }

                +irReturn(arrayOfCall)
            }
        }

        return methodClass
    }

    /**
     * Part of [generateRpcMethod] that generates next call:
     *
     * ```kotlin
     * __rpc_client.call(RpcCall(
     *     descriptor = Companion,
     *     callableName = "<method-name>",
     *     data = (<method-class>(<method-args>)|<method-object>),
     *     serviceId = __rpc_stub_id,
     * ))
     * ```
     */
    @Suppress("detekt.NestedBlockDepth")
    private fun IrBlockBodyBuilder.irRpcMethodClientCall(
        method: ServiceDeclaration.Method,
        functionThisReceiver: IrValueParameter,
        isMethodObject: Boolean,
        methodClass: IrClass,
        arguments: List<IrValueParameter>,
    ): IrCall {
        val callee = if (method.function.isNonSuspendingWithFlowReturn()) {
            ctx.functions.rpcClientCallServerStreaming.symbol
        } else {
            ctx.functions.rpcClientCall.symbol
        }

        val call = irCall(
            callee = callee,
            type = method.function.returnType,
            typeArgumentsCount = 1,
        ).apply {
            val rpcCallConstructor = irCallConstructor(
                callee = ctx.rpcCall.constructors.single(),
                typeArguments = emptyList(),
            ).apply {
                val dataParameter = if (isMethodObject) {
                    irGetObject(methodClass.symbol)
                } else {
                    irCallConstructor(
                        // serialization plugin adds additional constructor with more arguments
                        callee = methodClass.constructors.single {
                            vsApi {
                                it.valueParametersVS().size == method.arguments.size
                            }
                        }.symbol,
                        typeArguments = emptyList(),
                    ).apply {
                        arguments {
                            values {
                                arguments.forEach { valueParameter ->
                                    +irGet(valueParameter)
                                }
                            }
                        }
                    }
                }

                arguments {
                    values {
                        +irGetDescriptor()

                        +stringConst(method.function.name.asString())

                        +dataParameter

                        +irCallProperty(
                            clazz = stubClass,
                            property = stubIdProperty,
                            symbol = functionThisReceiver.symbol,
                        )
                    }
                }
            }

            arguments {
                dispatchReceiver = irCallProperty(
                    clazz = stubClass,
                    property = clientProperty,
                    symbol = functionThisReceiver.symbol,
                )

                types {
                    +method.function.returnType
                }

                values {
                    +rpcCallConstructor
                }
            }
        }

        return call
    }

    private fun irGetDescriptor(): IrExpression {
        val companion = stubClass.companionObject()
            ?: error("Expected companion object in ${stubClass.name}")

        return IrGetObjectValueImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            type = companion.symbol.defaultType,
            symbol = companion.symbol,
        )
    }

    private fun irCallProperty(
        clazz: IrClass,
        property: IrProperty,
        type: IrType? = null,
        symbol: IrValueSymbol? = null,
    ): IrCall {
        return irCallProperty(
            receiver = IrGetValueImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = type ?: clazz.defaultType,
                symbol = symbol
                    ?: clazz.thisReceiver?.symbol
                    ?: error("Expected thisReceiver for ${clazz.name.asString()}"),
            ),
            property = property,
        )
    }

    private fun irCallProperty(receiver: IrExpression, property: IrProperty): IrCall {
        val getter = property.getterOrFail

        return vsApi {
            IrCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = getter.returnType,
                symbol = getter.symbol,
                origin = IrStatementOrigin.GET_PROPERTY,
                valueArgumentsCount = vsApi { getter.valueParametersVS() }.size,
                typeArgumentsCount = getter.typeParameters.size,
            )
        }.apply {
            arguments {
                dispatchReceiver = receiver
            }
        }
    }

    private var stubCompanionObject: IrClassSymbol by Delegates.notNull()
    private var stubCompanionObjectThisReceiver: IrValueParameter by Delegates.notNull()

    /**
     * Companion object for the RPC service stub.
     * The empty object should already be generated
     *
     * ```kotlin
     *  companion object : RpcServiceDescriptor<MyService>
     * ```
     */
    private fun IrClass.generateCompanionObject() {
        companionObject()?.apply {
            // clearing previous declarations, as they may break with the ones added here
            declarations.clear()

            stubCompanionObject = symbol
            stubCompanionObjectThisReceiver = thisReceiver
                ?: error("Stub companion object expected to have thisReceiver: ${name.asString()}")

            superTypes = listOf(ctx.rpcServiceDescriptor.typeWith(declaration.serviceType))

            generateCompanionObjectConstructor()

            generateCompanionObjectContent()

            addAnyOverrides(ctx.rpcServiceDescriptor.owner)
        } ?: error("Expected companion object to be present")
    }

    private fun IrClass.generateCompanionObjectConstructor() {
        // default object constructor
        addConstructor {
            name = this@generateCompanionObjectConstructor.name
            isPrimary = true
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            addDefaultConstructor(this)
        }
    }

    private fun IrClass.generateCompanionObjectContent() {
        generateFqName()

        generateInvokators()

        generateCallableMapProperty()

        generateGetCallableFunction()

        generateCreateInstanceFunction()
    }

    /**
     * `fqName` property of the descriptor.
     *
     * ```kotlin
     * override val fqName = "MyService"
     * ```
     */
    private fun IrClass.generateFqName() {
        addProperty {
            name = Name.identifier(Descriptor.FQ_NAME)
            visibility = DescriptorVisibilities.PUBLIC
        }.apply {
            overriddenSymbols = listOf(ctx.properties.rpcServiceDescriptorFqName)

            addBackingFieldUtil {
                visibility = DescriptorVisibilities.PRIVATE
                type = ctx.irBuiltIns.stringType
                vsApi { isFinalVS = true }
            }.apply {
                initializer = factory.createExpressionBody(
                    stringConst(declaration.fqName)
                )
            }

            addDefaultGetter(this@generateFqName, ctx.irBuiltIns) {
                visibility = DescriptorVisibilities.PUBLIC
                overriddenSymbols = listOf(ctx.properties.rpcServiceDescriptorFqName.owner.getterOrFail.symbol)
            }
        }
    }

    private val invokators = mutableMapOf<String, IrProperty>()

    private fun IrClass.generateInvokators() {
        declaration.methods.forEachIndexed { i, callable ->
            generateInvokator(i, callable)
        }
    }

    /**
     * Generates an invokator (`RpcInvokator`) for this callable.
     *
     * For methods:
     * ```kotlin
     * private val <method-name>Invokator = RpcInvokator.Method<MyService> {
     *     service: MyService, data: Any? ->
     *
     *     val dataCasted = data.dataCast<<method-class-type>>(
     *         "<method-name>",
     *         "<service-name>",
     *     )
     *
     *     service.<method-name>(dataCasted.<parameter-1>, ..., $completion)
     * }
     * ```
     *
     * Where:
     *  - `<method-name>` - the name of the method
     *  - `<method-class-type>` - type of the corresponding method class
     *  - `<service-name>` - name of the service
     *  - `$completion` - Continuation<Any?> parameter
     *
     * For fields:
     * ```kotlin
     * private val <field-name>Invokator = RpcInvokator.Field<MyService> { service: MyService ->
     *     service.<field-name>
     * }
     * ```
     * Where:
     *  - `<field-name>` - the name of the field
     */
    @Suppress(
        "detekt.NestedBlockDepth",
        "detekt.LongMethod",
        "detekt.CyclomaticComplexMethod",
    )
    private fun IrClass.generateInvokator(i: Int, callable: ServiceDeclaration.Callable) {
        invokators[callable.name] = addProperty {
            name = Name.identifier("${callable.name}Invokator")
            visibility = DescriptorVisibilities.PRIVATE
        }.apply {
            val propertyType = ctx.rpcInvokatorMethod.typeWith(declaration.serviceType)

            addBackingFieldUtil {
                visibility = DescriptorVisibilities.PRIVATE
                type = propertyType
                vsApi { isFinalVS = true }
            }.apply backingField@{
                val functionLambda = factory.buildFun {
                    origin = IrDeclarationOrigin.LOCAL_FUNCTION_FOR_LAMBDA
                    name = SpecialNames.ANONYMOUS
                    visibility = DescriptorVisibilities.LOCAL
                    modality = Modality.FINAL
                    returnType = ctx.anyNullable
                    if (callable is ServiceDeclaration.Method) {
                        isSuspend = true
                    }
                }.apply {
                    parent = this@backingField

                    val serviceParameter = addValueParameter {
                        name = Name.identifier("service")
                        type = declaration.serviceType
                    }

                    val dataParameter = if (callable is ServiceDeclaration.Method) {
                        addValueParameter {
                            name = Name.identifier("data")
                            type = ctx.anyNullable
                        }
                    } else {
                        null
                    }

                    body = irBuilder(symbol).irBlockBody {
                        val call = when (callable) {
                            is ServiceDeclaration.Method -> {
                                val methodClass = methodClasses[i]
                                val dataCasted = irTemporary(
                                    value = irCall(ctx.functions.dataCast, type = methodClass.defaultType).apply {
                                        dataParameter ?: error("unreachable")

                                        arguments {
                                            extensionReceiver = irGet(dataParameter)

                                            types {
                                                +methodClass.defaultType
                                            }

                                            values {
                                                +stringConst(callable.name)
                                                +stringConst(declaration.fqName)
                                            }
                                        }
                                    },
                                    nameHint = "dataCasted",
                                    irType = methodClass.defaultType,
                                )

                                irCall(callable.function).apply {
                                    arguments {
                                        dispatchReceiver = irGet(serviceParameter)

                                        values {
                                            callable.arguments.forEach { arg ->
                                                +irCallProperty(
                                                    receiver = irGet(dataCasted),
                                                    property = methodClass.properties.single { it.name == arg.value.name },
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        +irReturn(call)
                    }
                }

                val lambdaType = when (callable) {
                    is ServiceDeclaration.Method -> ctx.suspendFunction2.typeWith(
                        declaration.serviceType, // service
                        ctx.anyNullable, // data
                        ctx.anyNullable, // returnType
                    )
                }

                val lambda = IrFunctionExpressionImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = lambdaType,
                    origin = IrStatementOrigin.LAMBDA,
                    function = functionLambda,
                )

                initializer = factory.createExpressionBody(
                    IrTypeOperatorCallImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = propertyType,
                        operator = IrTypeOperator.SAM_CONVERSION,
                        typeOperand = propertyType,
                        argument = lambda,
                    )
                )
            }

            addDefaultGetter(this@generateInvokator, ctx.irBuiltIns) {
                visibility = DescriptorVisibilities.PRIVATE
            }
        }
    }

    private var callableMap: IrProperty by Delegates.notNull()

    /**
     * Callable names map.
     * A map that holds an RpcCallable that describes it.
     *
     * ```kotlin
     *  private val callableMap: Map<String, RpcCallable<MyService>> = mapOf(
     *      Pair("<callable-name-1>", RpcCallable(...)),
     *      ...
     *      Pair("<callable-name-n>", RpcCallable(...)),
     *  )
     *
     *  // when n=0:
     *  private val callableMap: Map<String, RpcCallable<MyService>> = emptyMap()
     * ```
     *
     * Where:
     *  - `<callable-name-k>` - the name of the k-th callable in the service
     */
    private fun IrClass.generateCallableMapProperty() {
        callableMap = addProperty {
            name = Name.identifier(Descriptor.CALLABLE_MAP)
            visibility = DescriptorVisibilities.PRIVATE
            modality = Modality.FINAL
        }.apply {
            val rpcCallableType = ctx.rpcCallable.typeWith(declaration.serviceType)
            val mapType = ctx.irBuiltIns.mapClass.typeWith(ctx.irBuiltIns.stringType, rpcCallableType)

            addBackingFieldUtil {
                type = mapType
                vsApi { isFinalVS = true }
                visibility = DescriptorVisibilities.PRIVATE
            }.apply {
                val isEmpty = declaration.methods.isEmpty()

                initializer = factory.createExpressionBody(
                    vsApi {
                        IrCallImplVS(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            type = mapType,
                            symbol = if (isEmpty) ctx.functions.emptyMap else ctx.functions.mapOf,
                            valueArgumentsCount = if (isEmpty) 0 else 1,
                            typeArgumentsCount = 2,
                        )
                    }.apply mapApply@{
                        if (isEmpty) {
                            arguments {
                                types {
                                    +ctx.irBuiltIns.stringType
                                    +rpcCallableType
                                }
                            }

                            return@mapApply
                        }

                        val pairType = ctx.pair.typeWith(ctx.irBuiltIns.stringType, rpcCallableType)

                        val varargType = ctx.irBuiltIns.arrayClass.typeWith(pairType, Variance.OUT_VARIANCE)

                        val callables = declaration.methods

                        val vararg = IrVarargImpl(
                            startOffset = UNDEFINED_OFFSET,
                            endOffset = UNDEFINED_OFFSET,
                            type = varargType,
                            varargElementType = pairType,
                            elements = callables.memoryOptimizedMapIndexed { i, callable ->
                                vsApi {
                                    IrCallImplVS(
                                        startOffset = UNDEFINED_OFFSET,
                                        endOffset = UNDEFINED_OFFSET,
                                        type = pairType,
                                        symbol = ctx.functions.to,
                                        typeArgumentsCount = 2,
                                        valueArgumentsCount = 1,
                                    )
                                }.apply {
                                    arguments {
                                        types {
                                            +ctx.irBuiltIns.stringType
                                            +rpcCallableType
                                        }

                                        extensionReceiver = stringConst(callable.name)

                                        values {
                                            +irRpcCallable(i, callable)
                                        }
                                    }
                                }
                            },
                        )

                        arguments {
                            types {
                                +ctx.irBuiltIns.stringType
                                +rpcCallableType
                            }

                            values {
                                +vararg
                            }
                        }
                    }
                )
            }

            addDefaultGetter(this@generateCallableMapProperty, ctx.irBuiltIns) {
                visibility = DescriptorVisibilities.PRIVATE
            }
        }
    }

    /**
     * A call to constructor of the RpcCallable.
     *
     * ```kotlin
     * RpcCallable<MyService>(
     *     name = "<callable-name>",
     *     dataType = RpcCall(typeOf<<callable-data-type>>()),
     *     returnType = RpcCall(typeOf<<callable-return-type>>()),
     *     invokator = <callable-invokator>,
     *     parameters = arrayOf( // or emptyArray()
     *         RpcParameter(
     *             "<method-parameter-name-1>",
     *             RpcCall(typeOf<<method-parameter-type-1>>())
     *         ),
     *         ...
     *     ),
     *     isNonSuspendFunction = !function.isSuspend,
     * )
     *```
     *
     * Where:
     *  - `<callable-name>` - the name of the method (field)
     *  - `<callable-data-type>` - a method class for a method and `FieldDataObject` for fields
     *  - `<callable-return-type>` - the return type for the method and the field type for a field.
     *  For a non-suspending flow the return type is its element type
     *  - `<callable-invokator>` - an invokator, previously generated by [generateInvokators]
     *  - `<method-parameter-name-k>` - if a method, its k-th parameter name
     *  - `<method-parameter-type-k>` - if a method, its k-th parameter type
     */
    private fun irRpcCallable(i: Int, callable: ServiceDeclaration.Callable): IrExpression {
        return vsApi {
            IrConstructorCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = ctx.rpcCallable.typeWith(declaration.serviceType),
                symbol = ctx.rpcCallable.constructors.single(),
                typeArgumentsCount = 1,
                valueArgumentsCount = 5,
                constructorTypeArgumentsCount = 1,
            )
        }.apply {
            putConstructorTypeArgument(0, declaration.serviceType)

            val dataType = when (callable) {
                is ServiceDeclaration.Method -> methodClasses[i].defaultType
            }

            val returnType = when {
                callable.function.isNonSuspendingWithFlowReturn() -> {
                    (callable.function.returnType as IrSimpleType).arguments.single().typeOrFail
                }

                else -> {
                    callable.function.returnType
                }
            }

            val invokator = invokators[callable.name]
                ?: error("Expected invokator for ${callable.name} in ${declaration.service.name}")

            val parameters = callable.arguments

            val callee = if (parameters.isEmpty()) {
                ctx.functions.emptyArray
            } else {
                ctx.irBuiltIns.arrayOf
            }

            val arrayParametersType = ctx.irBuiltIns.arrayClass.typeWith(
                ctx.rpcParameter.defaultType,
                Variance.OUT_VARIANCE,
            )

            val arrayOfCall = vsApi {
                IrCallImplVS(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = arrayParametersType,
                    symbol = callee,
                    typeArgumentsCount = 1,
                    valueArgumentsCount = if (parameters.isEmpty()) 0 else 1,
                )
            }.apply arrayOfCall@{
                if (parameters.isEmpty()) {
                    arguments {
                        types { +ctx.rpcParameter.defaultType }
                    }

                    return@arrayOfCall
                }

                val vararg = IrVarargImpl(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = arrayParametersType,
                    varargElementType = ctx.rpcParameter.defaultType,
                    elements = parameters.memoryOptimizedMap { parameter ->
                        vsApi {
                            IrConstructorCallImplVS(
                                startOffset = UNDEFINED_OFFSET,
                                endOffset = UNDEFINED_OFFSET,
                                type = ctx.rpcParameter.defaultType,
                                symbol = ctx.rpcParameter.constructors.single(),
                                typeArgumentsCount = 0,
                                constructorTypeArgumentsCount = 0,
                                valueArgumentsCount = 2,
                            )
                        }.apply {
                            arguments {
                                values {
                                    +stringConst(parameter.value.name.asString())
                                    +irRpcTypeCall(parameter.type)
                                }
                            }
                        }
                    },
                )

                arguments {
                    types {
                        +ctx.rpcParameter.defaultType
                    }

                    values {
                        +vararg
                    }
                }
            }

            arguments {
                values {
                    +stringConst(callable.name)

                    +irRpcTypeCall(dataType)

                    +irRpcTypeCall(returnType)

                    +irCallProperty(stubCompanionObject.owner, invokator)

                    +arrayOfCall

                    +booleanConst(!callable.function.isSuspend)
                }
            }
        }
    }

    private fun IrSimpleFunction.isNonSuspendingWithFlowReturn(): Boolean {
        return returnType.classOrNull == ctx.flow && !isSuspend
    }

    /**
     * Accessor function for the `callableMap` property
     * Defined in `RpcServiceDescriptor`
     *
     * ```kotlin
     *  final override fun getCallable(name: String): RpcCallable<MyService>? = callableMap[name]
     * ```
     */
    private fun IrClass.generateGetCallableFunction() {
        val resultType = ctx.rpcCallable.createType(hasQuestionMark = true, emptyList())

        addFunction {
            name = Name.identifier(Descriptor.GET_CALLABLE)
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            returnType = resultType
        }.apply {
            overriddenSymbols = listOf(ctx.rpcServiceDescriptor.functionByName(Descriptor.GET_CALLABLE))

            val functionThisReceiver = vsApi {
                stubCompanionObjectThisReceiver.copyToVS(this@apply, origin = IrDeclarationOrigin.DEFINED)
            }.also {
                vsApi {
                    dispatchReceiverParameterVS = it
                }
            }

            val parameter = addValueParameter {
                name = Name.identifier("name")
                type = ctx.irBuiltIns.stringType
            }

            body = irBuilder(symbol).irBlockBody {
                +irReturn(
                    irCall(ctx.functions.mapGet.symbol, resultType).apply {
                        vsApi { originVS = IrStatementOrigin.GET_ARRAY_ELEMENT }

                        arguments {
                            dispatchReceiver = irCallProperty(
                                clazz = this@generateGetCallableFunction,
                                property = callableMap,
                                symbol = functionThisReceiver.symbol,
                            )

                            values {
                                +irGet(parameter)
                            }
                        }
                    }
                )
            }
        }
    }

    /**
     * Factory method for creating a new instance of RPC service
     *
     * ```kotlin
     *  final override fun createInstance(serviceId: Long, client: RpcClient): MyService {
     *      return `$rpcServiceStub`(serviceId, client)
     *  }
     * ```
     */
    private fun IrClass.generateCreateInstanceFunction() {
        addFunction {
            name = Name.identifier(Descriptor.CREATE_INSTANCE)
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            returnType = declaration.serviceType
        }.apply {
            overriddenSymbols = listOf(ctx.rpcServiceDescriptor.functionByName(Descriptor.CREATE_INSTANCE))

            vsApi {
                dispatchReceiverParameterVS = stubCompanionObjectThisReceiver
                    .copyToVS(this@apply, origin = IrDeclarationOrigin.DEFINED)
            }

            val serviceId = addValueParameter {
                name = Name.identifier("serviceId")
                type = ctx.irBuiltIns.longType
            }

            val client = addValueParameter {
                name = Name.identifier("client")
                type = ctx.rpcClient.defaultType
            }

            body = irBuilder(symbol).irBlockBody {
                +irReturn(
                    irCallConstructor(
                        callee = stubClass.constructors.single().symbol,
                        typeArguments = emptyList(),
                    ).apply {
                        arguments {
                            values {
                                +irGet(serviceId)
                                +irGet(client)
                            }
                        }
                    }
                )
            }
        }
    }

    // Associated object annotation works on JS, WASM, and Native platforms.
    // See https://kotlinlang.org/api/latest/jvm/stdlib/kotlin.reflect/find-associated-object.html
    private fun addAssociatedObjectAnnotationIfPossible() {
        if (ctx.isJsTarget() || ctx.isNativeTarget() || ctx.isWasmTarget()) {
            addAssociatedObjectAnnotation()
        }
    }

    private fun addAssociatedObjectAnnotation() {
        val service = declaration.service

        service.annotations += vsApi {
            IrConstructorCallImplVS(
                startOffset = service.startOffset,
                endOffset = service.endOffset,
                type = ctx.withServiceDescriptor.defaultType,
                symbol = ctx.withServiceDescriptor.constructors.single(),
                typeArgumentsCount = 0,
                constructorTypeArgumentsCount = 0,
                valueArgumentsCount = 1,
            )
        }.apply {
            val companionObjectType = stubCompanionObject.defaultType
            arguments {
                values {
                    +IrClassReferenceImpl(
                        startOffset = startOffset,
                        endOffset = endOffset,
                        type = ctx.irBuiltIns.kClassClass.typeWith(companionObjectType),
                        symbol = stubCompanionObject,
                        classType = companionObjectType,
                    )
                }
            }
        }
    }

    /**
     * IR call of the `RpcType(KType)` function
     */
    private fun irRpcTypeCall(type: IrType): IrConstructorCallImpl {
        return vsApi {
            IrConstructorCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = ctx.rpcType.defaultType,
                symbol = ctx.rpcType.constructors.single(),
                typeArgumentsCount = 0,
                valueArgumentsCount = 1,
                constructorTypeArgumentsCount = 0,
            )
        }.apply {
            arguments {
                values {
                    +irTypeOfCall(type)
                }
            }
        }
    }

    /**
     * IR call of the `typeOf<...>()` function
     */
    private fun irTypeOfCall(type: IrType): IrCall {
        return vsApi {
            IrCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = ctx.kTypeClass.defaultType,
                symbol = ctx.functions.typeOf,
                typeArgumentsCount = 1,
                valueArgumentsCount = 0,
            )
        }.apply {
            arguments {
                types { +type }
            }
        }
    }

    // default constructor implementation
    private fun IrClass.addDefaultConstructor(constructor: IrConstructor) {
        constructor.body = irBuilder(constructor.symbol).irBlockBody {
            +irDelegatingConstructorCall(context.irBuiltIns.anyClass.owner.constructors.single())
            +IrInstanceInitializerCallImpl(
                startOffset = startOffset,
                endOffset = endOffset,
                classSymbol = this@addDefaultConstructor.symbol,
                type = context.irBuiltIns.unitType,
            )
        }
    }

    // adds fake overrides for toString(), equals(), hashCode() for a class
    private fun IrClass.addAnyOverrides(parent: IrClass? = null) {
        val anyClass = ctx.irBuiltIns.anyClass.owner
        val overriddenClass = parent ?: anyClass

        addFunction {
            name = OperatorNameConventions.EQUALS
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            origin = IrDeclarationOrigin.FAKE_OVERRIDE

            isOperator = true
            isFakeOverride = true
            returnType = ctx.irBuiltIns.booleanType
        }.apply {
            overriddenSymbols += overriddenClass.functions.single {
                it.name == OperatorNameConventions.EQUALS
            }.symbol

            vsApi {
                dispatchReceiverParameterVS = anyClass.copyThisReceiver(this@apply)
            }

            addValueParameter {
                name = Name.identifier("other")
                type = ctx.anyNullable
            }
        }

        addFunction {
            name = OperatorNameConventions.HASH_CODE
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            origin = IrDeclarationOrigin.FAKE_OVERRIDE

            isFakeOverride = true
            returnType = ctx.irBuiltIns.intType
        }.apply {
            overriddenSymbols += overriddenClass.functions.single {
                it.name == OperatorNameConventions.HASH_CODE
            }.symbol

            vsApi {
                dispatchReceiverParameterVS = anyClass.copyThisReceiver(this@apply)
            }
        }

        addFunction {
            name = OperatorNameConventions.TO_STRING
            visibility = DescriptorVisibilities.PUBLIC
            modality = Modality.OPEN
            origin = IrDeclarationOrigin.FAKE_OVERRIDE

            isFakeOverride = true
            returnType = ctx.irBuiltIns.stringType
        }.apply {
            overriddenSymbols += overriddenClass.functions.single {
                it.name == OperatorNameConventions.TO_STRING
            }.symbol

            vsApi {
                dispatchReceiverParameterVS = anyClass.copyThisReceiver(this@apply)
            }
        }
    }

    private fun IrClass.copyThisReceiver(function: IrFunction) = vsApi {
        thisReceiver?.copyToVS(function, origin = IrDeclarationOrigin.DEFINED)
    }

    private fun stringConst(value: String) = IrConstImpl.string(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = ctx.irBuiltIns.stringType,
        value = value,
    )

    private fun booleanConst(value: Boolean) = IrConstImpl.boolean(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = ctx.irBuiltIns.booleanType,
        value = value,
    )

    private inline fun <T> vsApi(body: VersionSpecificApi.() -> T): T {
        return ctx.versionSpecificApi.body()
    }

    private inline fun IrMemberAccessExpression<*>.arguments(body: IrMemberAccessExpressionBuilder.() -> Unit) {
        return arguments(ctx.versionSpecificApi, body)
    }
}
