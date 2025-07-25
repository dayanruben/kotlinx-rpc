/*
 * Copyright 2023-2025 JetBrains s.r.o and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package kotlinx.rpc.codegen.extension

import kotlinx.rpc.codegen.VersionSpecificApi
import kotlinx.rpc.codegen.common.RpcClassId
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
                            arguments = arguments,
                        )
                    )

                    return@irBlockBody
                }

                val call = irRpcMethodClientCall(
                    method = method,
                    functionThisReceiver = functionThisReceiver,
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
     * Part of [generateRpcMethod] that generates next call:
     *
     * ```kotlin
     * __rpc_client.call(RpcCall(
     *     descriptor = Companion,
     *     callableName = "<method-name>",
     *     parameters = arrayOf(<method-arg-0>, ...),
     *     serviceId = __rpc_stub_id,
     * ))
     * ```
     */
    @Suppress("detekt.NestedBlockDepth")
    private fun IrBlockBodyBuilder.irRpcMethodClientCall(
        method: ServiceDeclaration.Method,
        functionThisReceiver: IrValueParameter,
        arguments: List<IrValueParameter>,
    ): IrCall {
        val clientCallee = if (method.function.isNonSuspendingWithFlowReturn()) {
            ctx.functions.rpcClientCallServerStreaming.symbol
        } else {
            ctx.functions.rpcClientCall.symbol
        }

        val call = irCall(
            callee = clientCallee,
            type = method.function.returnType,
            typeArgumentsCount = 1,
        ).apply {
            val rpcCallConstructor = irCallConstructor(
                callee = ctx.rpcCall.constructors.single(),
                typeArguments = emptyList(),
            ).apply {
                val callee = if (arguments.isEmpty()) {
                    ctx.functions.emptyArray
                } else {
                    ctx.irBuiltIns.arrayOf
                }

                val parametersParameter = irCall(callee, type = ctx.arrayOfAnyNullable).apply arrayOfCall@{
                    if (arguments.isEmpty()) {
                        arguments {
                            types { +ctx.anyNullable }
                        }

                        return@arrayOfCall
                    }

                    val vararg = irVararg(
                        elementType = ctx.anyNullable,
                        values = arguments.memoryOptimizedMap { valueParameter ->
                            irGet(valueParameter)
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

                arguments {
                    values {
                        +irGetDescriptor()

                        +stringConst(method.function.name.asString())

                        +parametersParameter

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
        declaration.methods.forEach { callable ->
            generateInvokator(callable)
        }
    }

    /**
     * Generates an invokator (`RpcInvokator`) for this callable.
     *
     * For methods:
     * ```kotlin
     * private val <method-name>Invokator = RpcInvokator.Method<MyService> {
     *     service: MyService, parameters: Array<Any?> ->
     *
     *     service.<method-name>(parameters[0] as<?> <parameter-type-1>, ..., $completion)
     * }
     * ```
     *
     * Where:
     *  - `<method-name>` - the name of the method
     *  - <parameter-type-k> - type of the kth parameter
     *  - `$completion` - Continuation<Any?> parameter
     */
    @Suppress(
        "detekt.NestedBlockDepth",
        "detekt.LongMethod",
        "detekt.CyclomaticComplexMethod",
    )
    private fun IrClass.generateInvokator(callable: ServiceDeclaration.Callable) {
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

                    val parametersParameter = when (callable) {
                        is ServiceDeclaration.Method -> addValueParameter {
                            name = Name.identifier("parameters")
                            type = ctx.arrayOfAnyNullable
                        }
                    }

                    body = irBuilder(symbol).irBlockBody {
                        val call = irCall(callable.function).apply {
                            arguments {
                                dispatchReceiver = irGet(serviceParameter)

                                values {
                                    callable.arguments.forEachIndexed { argIndex, arg ->
                                        val parameter = irCall(
                                            callee = ctx.functions.arrayGet.symbol,
                                            type = ctx.anyNullable,
                                        ).apply {
                                            vsApi { originVS = IrStatementOrigin.GET_ARRAY_ELEMENT }

                                            arguments {
                                                dispatchReceiver = irGet(parametersParameter)

                                                values {
                                                    +intConst(argIndex)
                                                }
                                            }
                                        }

                                        if (vsApi { arg.type.isNullableVS() }) {
                                            +irSafeAs(parameter, arg.type)
                                        } else {
                                            +irAs(parameter, arg.type)
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
                    irMapOf(
                        keyType = ctx.irBuiltIns.stringType,
                        valueType = rpcCallableType,
                        elements = declaration.methods.map { callable ->
                            stringConst(callable.name) to irRpcCallable(callable)
                        },
                        isEmpty = isEmpty,
                    )
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
     *             RpcCall(typeOf<<method-parameter-type-1>>(), <method-parameter-type-1>.annotations),
     *             <method-parameter-1-annotations-list>,
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
    private fun irRpcCallable(callable: ServiceDeclaration.Callable): IrExpression {
        return vsApi {
            IrConstructorCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = ctx.rpcCallable.typeWith(declaration.serviceType),
                symbol = ctx.rpcCallableDefault.constructors.single(),
                typeArgumentsCount = 1,
                valueArgumentsCount = 5,
                constructorTypeArgumentsCount = 1,
            )
        }.apply {
            putConstructorTypeArgument(0, declaration.serviceType)

            callable as ServiceDeclaration.Method

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
                                symbol = ctx.rpcParameterDefault.constructors.single(),
                                typeArgumentsCount = 0,
                                constructorTypeArgumentsCount = 0,
                                valueArgumentsCount = 3,
                            )
                        }.apply {
                            arguments {
                                values {
                                    +stringConst(parameter.value.name.asString())
                                    +irRpcTypeCall(parameter.type)
                                    +booleanConst(parameter.isOptional)
                                    +irListOfAnnotations(parameter.value)
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

                    +irRpcTypeCall(returnType)

                    +irCallProperty(stubCompanionObject.owner, invokator)

                    +arrayOfCall

                    +booleanConst(!callable.function.isSuspend)
                }
            }
        }
    }

    private fun irListOfAnnotations(container: IrAnnotationContainer): IrCallImpl {
        val isEmpty = container.annotations.isEmpty()
        return vsApi {
            IrCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                symbol = if (isEmpty) ctx.functions.emptyList else ctx.functions.listOf,
                type = ctx.listOfAnnotations,
                typeArgumentsCount = 1,
                valueArgumentsCount = if (isEmpty) 0 else 1,
            )
        }.apply applyIrListOfAnnotations@{
            arguments {
                types { +ctx.irBuiltIns.annotationType }

                if (isEmpty) {
                    return@applyIrListOfAnnotations
                }

                values {
                    +IrVarargImpl(
                        startOffset = UNDEFINED_OFFSET,
                        endOffset = UNDEFINED_OFFSET,
                        type = ctx.arrayOfAnnotations,
                        varargElementType = ctx.irBuiltIns.annotationType,
                        elements = container.annotations,
                    )
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
     * IR call of the `RpcType(KType, List<Annotation>)` function
     */
    private fun irRpcTypeCall(type: IrType): IrConstructorCallImpl {
        // todo change to extension after KRPC-178
        val withSerializableAnnotations = type.annotations.any {
            it.type.isSerializableAnnotation()
        }

        return vsApi {
            IrConstructorCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = ctx.rpcType.defaultType,
                symbol = (if (withSerializableAnnotations) ctx.rpcTypeKrpc else ctx.rpcTypeDefault)
                    .constructors.single(),
                typeArgumentsCount = 0,
                valueArgumentsCount = if (withSerializableAnnotations) 3 else 2,
                constructorTypeArgumentsCount = 0,
            )
        }.apply {
            arguments {
                values {
                    +irTypeOfCall(type)
                    +irListOfAnnotations(type)

                    if (withSerializableAnnotations) {
                        +irMapOf(
                            keyType = ctx.kSerializerAnyNullableKClass,
                            valueType = ctx.kSerializerAnyNullable,
                            elements = type.annotations
                                .filter { it.type.isSerializableAnnotation() }
                                .memoryOptimizedMap {
                                    val kClassValue = vsApi { it.argumentsVS }.singleOrNull()
                                            as? IrClassReference
                                        ?: error("Expected single not null value parameter of KSerializer::class for @Serializable annotation on type '${type.dumpKotlinLike()}'")

                                    kClassValue to kClassValue.classType.irCreateInstance()
                                }
                        )
                    }
                }
            }
        }
    }

    private fun IrType.irCreateInstance(): IrExpression {
        val classSymbol =
            classOrNull ?: error("Expected class type for type to create instance '${dumpKotlinLike()}'")

        return if (classSymbol.owner.isObject) {
            IrGetObjectValueImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = this,
                symbol = classSymbol,
            )
        } else {
            val constructor = classSymbol.owner.primaryConstructor
                ?: error("Expected primary constructor for a serializer '${dumpKotlinLike()}'")

            if (vsApi { constructor.parametersVS.isNotEmpty() }) {
                error(
                    "Primary constructor for a serializer '${dumpKotlinLike()}' can't have parameters: " +
                            vsApi { constructor.parametersVS }.joinToString { it.dumpKotlinLike() }
                )
            }

            vsApi {
                IrConstructorCallImplVS(
                    startOffset = UNDEFINED_OFFSET,
                    endOffset = UNDEFINED_OFFSET,
                    type = ctx.rpcType.defaultType,
                    symbol = constructor.symbol,
                    typeArgumentsCount = constructor.typeParameters.size,
                    valueArgumentsCount = 0,
                    constructorTypeArgumentsCount = constructor.typeParameters.size,
                )
            }.apply {
                arguments {
                    types {
                        repeat(classSymbol.owner.typeParameters.size) {
                            +ctx.anyNullable
                        }
                    }
                }
            }
        }
    }

    private fun IrType.isSerializableAnnotation(): Boolean =
        classOrNull?.owner?.classId == RpcClassId.serializableAnnotation

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

    private fun irMapOf(
        keyType: IrType,
        valueType: IrType,
        elements: List<Pair<IrExpression, IrExpression>>,
        isEmpty: Boolean = elements.isEmpty(),
    ): IrCallImpl {
        return vsApi {
            IrCallImplVS(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = ctx.irBuiltIns.mapClass.typeWith(keyType, valueType),
                symbol = if (isEmpty) ctx.functions.emptyMap else ctx.functions.mapOf,
                typeArgumentsCount = 2,
                valueArgumentsCount = if (isEmpty) 0 else 1,
            )
        }.apply mapApply@{
            if (isEmpty) {
                arguments {
                    types {
                        +keyType
                        +valueType
                    }
                }

                return@mapApply
            }

            val pairType = ctx.pair.typeWith(keyType, valueType)

            val varargType = ctx.irBuiltIns.arrayClass.typeWith(pairType, Variance.OUT_VARIANCE)

            val vararg = IrVarargImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                type = varargType,
                varargElementType = pairType,
                elements = elements.memoryOptimizedMap { (key, value) ->
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
                                +keyType
                                +valueType
                            }

                            extensionReceiver = key

                            values {
                                +value
                            }
                        }
                    }
                },
            )

            arguments {
                types {
                    +keyType
                    +valueType
                }

                values {
                    +vararg
                }
            }
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

    private fun intConst(value: Int) = IrConstImpl.int(
        startOffset = UNDEFINED_OFFSET,
        endOffset = UNDEFINED_OFFSET,
        type = ctx.irBuiltIns.intType,
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

    fun IrBuilderWithScope.irSafeAs(argument: IrExpression, type: IrType) =
        IrTypeOperatorCallImpl(startOffset, endOffset, type, IrTypeOperator.SAFE_CAST, type, argument)
}
