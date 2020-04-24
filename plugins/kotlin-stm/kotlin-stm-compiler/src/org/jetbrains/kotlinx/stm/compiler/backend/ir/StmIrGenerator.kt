/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.BackendContext
import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.ir.copyTo
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.parents
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedReceiverParameterDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeBuilder
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.TypeProjectionImpl
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.types.replace
import org.jetbrains.kotlin.types.typeUtil.makeNotNullable
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlinx.stm.compiler.*
import org.jetbrains.kotlinx.stm.compiler.extensions.FunctionTransformMap
import org.jetbrains.kotlinx.stm.compiler.extensions.StmResolveExtension

// Is creating synthetic origin is a good idea or not?
object STM_PLUGIN_ORIGIN : IrDeclarationOriginImpl("STM")

val BackendContext.externalSymbols: ReferenceSymbolTable get() = ir.symbols.externalSymbolTable


interface IrBuilderExtension {
    val compilerContext: IrPluginContext

    private fun IrDeclarationParent.declareSimpleFunctionWithExternalOverrides(descriptor: FunctionDescriptor): IrSimpleFunction {
        return compilerContext.symbolTable.declareSimpleFunction(startOffset, endOffset, STM_PLUGIN_ORIGIN, descriptor)
            .also { f ->
                f.overriddenSymbols = f.overriddenSymbols + descriptor.overriddenDescriptors.map {
                    compilerContext.symbolTable.referenceSimpleFunction(it.original)
                }
            }
    }

    fun IrFunction.createParameterDeclarations(
        overwriteValueParameters: Boolean = false
    ) {
        fun ParameterDescriptor.irValueParameter() = IrValueParameterImpl(
            this@createParameterDeclarations.startOffset, this@createParameterDeclarations.endOffset,
            STM_PLUGIN_ORIGIN,
            this,
            type.toIrType(),
            null
        ).also {
            it.parent = this@createParameterDeclarations
        }

        dispatchReceiverParameter = descriptor.dispatchReceiverParameter?.irValueParameter()
        extensionReceiverParameter = descriptor.extensionReceiverParameter?.irValueParameter()

        if (!overwriteValueParameters)
            assert(valueParameters.isEmpty())
        else
            valueParameters = emptyList()
        valueParameters = valueParameters + descriptor.valueParameters.map { it.irValueParameter() }

        assert(typeParameters.isEmpty())
    }

    fun IrDeclarationParent.contributeFunction(
        descriptor: FunctionDescriptor,
        declareNew: Boolean = false,
        bodyGen: IrBlockBodyBuilder.(IrFunction) -> Unit
    ): IrFunction {
        val f = if (declareNew) declareSimpleFunctionWithExternalOverrides(
            descriptor
        ) else compilerContext.symbolTable.referenceSimpleFunction(descriptor).owner

        f.parent = this
        f.returnType = descriptor.returnType!!.toIrType()
        if (declareNew) f.createParameterDeclarations()

        f.body = DeclarationIrBuilder(compilerContext, f.symbol, this.startOffset, this.endOffset).irBlockBody(
            this.startOffset,
            this.endOffset
        ) { bodyGen(f) }

        return f
    }

    fun declareFunction(
        parent: IrDeclarationParent,
        descriptor: FunctionDescriptor,
        declareNew: Boolean = false,
        bodyGen: IrBlockBodyBuilder.(IrFunction) -> Unit = {}
    ) = parent.contributeFunction(descriptor, declareNew, bodyGen)

    fun IrClass.initField(
        f: IrField,
        initGen: IrBuilderWithScope.() -> IrExpression
    ) {
        val builder = DeclarationIrBuilder(compilerContext, f.symbol, this.startOffset, this.endOffset)

        f.initializer = builder.irExprBody(builder.initGen())
    }

    fun IrBuilderWithScope.irInvoke(
        dispatchReceiver: IrExpression? = null,
        callee: IrFunctionSymbol,
        vararg args: IrExpression,
        typeHint: IrType? = null
    ): IrMemberAccessExpression {
        val returnType = typeHint ?: callee.descriptor.returnType!!.toIrType()
        val call = irCall(callee, type = returnType)
        call.dispatchReceiver = dispatchReceiver
        args.forEachIndexed(call::putValueArgument)
        return call
    }

    fun IrBuilderWithScope.irGetObject(classDescriptor: ClassDescriptor) =
        IrGetObjectValueImpl(
            startOffset,
            endOffset,
            classDescriptor.defaultType.toIrType(),
            compilerContext.symbolTable.referenceClass(classDescriptor)
        )

    fun <T : IrDeclaration> T.buildWithScope(builder: (T) -> Unit): T =
        also { irDeclaration ->
            compilerContext.symbolTable.withScope(irDeclaration.descriptor) {
                builder(irDeclaration)
            }
        }

    fun KotlinType.toIrType() = compilerContext.typeTranslator.translateType(this)

}

internal fun BackendContext.createTypeTranslator(moduleDescriptor: ModuleDescriptor): TypeTranslator =
    TypeTranslator(externalSymbols, irBuiltIns.languageVersionSettings, moduleDescriptor.builtIns).apply {
        constantValueGenerator = ConstantValueGenerator(moduleDescriptor, symbolTable = externalSymbols)
        constantValueGenerator.typeTranslator = this
    }

private fun isStmContextType(type: IrType?) = type?.classOrNull?.isClassWithFqName(STM_CONTEXT_CLASS.toUnsafe())
    ?: false

internal fun fetchStmContextOrNull(functionStack: MutableList<IrFunction>): IrGetValue? {
    val ctx = functionStack.firstNotNullResult {
        when {
            isStmContextType(it.dispatchReceiverParameter?.type) -> {
                it.dispatchReceiverParameter!!
            }
            isStmContextType(it.extensionReceiverParameter?.type) -> {
                it.extensionReceiverParameter!!
            }
            isStmContextType(it.valueParameters.lastOrNull()?.type) -> {
                it.valueParameters.last()
            }
            else -> null
        }
    }
        ?: return null

    return IrGetValueImpl(ctx.startOffset, ctx.endOffset, ctx.type, ctx.symbol)
}

class STMGenerator(override val compilerContext: IrPluginContext) : IrBuilderExtension {

    fun generateSTMField(irClass: IrClass, field: IrField, initMethod: IrFunctionSymbol, stmSearcherClass: ClassDescriptor) =
        irClass.initField(field) {
            val obj = irGetObject(stmSearcherClass)
            irCallOp(initMethod, field.type, obj)
        }

    fun createReceiverParam(
        type: IrType,
        paramDesc: ReceiverParameterDescriptor,
        name: String,
        index: Int
    ): IrValueParameter {
        val paramSymbol = IrValueParameterSymbolImpl(paramDesc)
        val param = IrValueParameterImpl(
            startOffset = UNDEFINED_OFFSET,
            endOffset = UNDEFINED_OFFSET,
            origin = IrDeclarationOrigin.DEFINED,
            symbol = paramSymbol,
            name = Name.identifier(name),
            index = index,
            type = type,
            varargElementType = null,
            isCrossinline = false,
            isNoinline = false
        )

        return param
    }

    fun wrapFunctionIntoTransaction(
        irClass: IrClass,
        irFunction: IrSimpleFunction,
        stmField: IrField,
        runAtomically: IrFunctionSymbol,
        stmContextType: IrType
    ) {
        val functionDescriptor = irFunction.descriptor

        irClass.contributeFunction(functionDescriptor) {

            val ctxReceiverDescriptor = WrappedReceiverParameterDescriptor()
            val ctxReceiver = createReceiverParam(stmContextType, ctxReceiverDescriptor, "ctx", index = 0)
            ctxReceiverDescriptor.bind(ctxReceiver)

            val funReturnType = irFunction.returnType

            val lambdaDescriptor = WrappedSimpleFunctionDescriptor()
            val irLambda = IrFunctionImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = STM_PLUGIN_ORIGIN,
                symbol = IrSimpleFunctionSymbolImpl(lambdaDescriptor),
                name = irFunction.name,
                visibility = Visibilities.LOCAL,
                modality = functionDescriptor.modality,
                returnType = funReturnType,
                isInline = irFunction.isInline,
                isExternal = irFunction.isExternal,
                isTailrec = irFunction.isTailrec,
                isSuspend = irFunction.isSuspend,
                isExpect = irFunction.isExpect,
                isFakeOverride = false,
                isOperator = false
            ).apply {
                lambdaDescriptor.bind(this)
                parent = irFunction
                extensionReceiverParameter = ctxReceiver
                body = DeclarationIrBuilder(compilerContext, this.symbol, irFunction.startOffset, irFunction.endOffset).irBlockBody(
                    this.startOffset,
                    this.endOffset
                ) {
                    irFunction.body?.deepCopyWithSymbols(initialParent = this@apply)?.statements?.forEach { st ->
                        when (st) {
                            is IrReturn -> +irReturn(st.value)
                            else -> +st
                        }
                    }
                }
                patchDeclarationParents(this)
            }

            val lambdaType = runAtomically.descriptor.valueParameters[1].type.replace(
                listOf(
                    TypeProjectionImpl(
                        Variance.INVARIANT,
                        runAtomically.descriptor.valueParameters[0].type.makeNotNullable(),
                    ),
                    TypeProjectionImpl(
                        Variance.INVARIANT,
                        funReturnType.toKotlinType()
                    )
                )
            ).toIrType()

            val lambdaExpression = IrFunctionExpressionImpl(
                irLambda.startOffset, irLambda.endOffset,
                lambdaType,
                irLambda,
                IrStatementOrigin.LAMBDA
            )

            val stmFieldExpr = irGetField(irGet(irFunction.dispatchReceiverParameter!!), stmField)

            val functionStack = irFunction.parents.mapNotNull { it as? IrFunction }.toMutableList()

            /* Note: this is needed for the case when we are transforming fake overrides.
             Fake overrides must now be also a real defined functions,
             example:
             override fun toString(): String {
                return runAtomically { super.toString() }
             }
             */
            it.origin = IrDeclarationOrigin.DEFINED

            +irReturn(
                irInvoke(
                    dispatchReceiver = stmFieldExpr,
                    callee = runAtomically,
                    args = *arrayOf(
                        fetchStmContextOrNull(functionStack)
                            ?: irNull(runAtomically.descriptor.valueParameters[0].type.toIrType()),
                        lambdaExpression
                    ),
                    typeHint = funReturnType
                ).apply {
                    putTypeArgument(index = 0, type = funReturnType)
                }
            )
        }
    }

    fun addDelegateField(
        irClass: IrClass,
        propertyName: Name,
        backingField: IrField,
        stmField: IrField,
        wrap: IrFunctionSymbol,
        universalDelegateClassSymbol: IrClassSymbol
    ): IrField {

        val delegateType = IrSimpleTypeBuilder().run {
            classifier = universalDelegateClassSymbol
            hasQuestionMark = false
            val type = backingField.type

            arguments = listOf(
                makeTypeProjection(type, Variance.INVARIANT)
            )
            buildSimpleType()
        }

        val delegateField = irClass.addField {
            name = Name.identifier("${propertyName}${SHARABLE_NAME_SUFFIX}")
            type = delegateType
            visibility = Visibilities.PRIVATE
            origin = IrDeclarationOrigin.DELEGATED_MEMBER
            isFinal = true
            isStatic = false
            buildField()
        }

        irClass.initField(delegateField) {
            val stmFieldExpr = irGetField(irGet(irClass.thisReceiver!!), stmField)

            val initValue = backingField.initializer?.expression ?: irNull(backingField.type)

            irInvoke(
                dispatchReceiver = stmFieldExpr,
                callee = wrap,
                args = *arrayOf(initValue),
                typeHint = delegateField.type
            ).apply {
                putTypeArgument(index = 0, type = backingField.type)
            }
        }

        return delegateField
    }

    private fun IrClass.findMethodDescriptor(name: Name) = this.declarations
        .find { it.nameForIrSerialization == name }
        ?.descriptor
        ?.safeAs<FunctionDescriptor>()

    fun addGetFunction(
        irClass: IrClass,
        propertyName: Name,
        delegate: IrField,
        stmField: IrField,
        getVar: IrFunctionSymbol
    ): IrFunction? {
        val getterFunDescriptor =
            irClass.findMethodDescriptor(StmResolveExtension.getterName(propertyName))
                ?: return null

        return irClass.contributeFunction(getterFunDescriptor, declareNew = true) { f ->
            val stmFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), stmField)

            val stmContextParam = f.valueParameters[0]

            val delegateFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), delegate)

            +irReturn(
                irInvoke(
                    dispatchReceiver = stmFieldExpr,
                    callee = getVar,
                    args = *arrayOf(irGet(stmContextParam), delegateFieldExpr),
                    typeHint = getterFunDescriptor.returnType?.toIrType()
                ).apply {
                    putTypeArgument(
                        index = 0,
                        type = delegateFieldExpr.type
                            .safeAs<IrSimpleType>()
                            ?.arguments
                            ?.first()
                            ?.typeOrNull
                            ?: throw StmLoweringException("Expected delegate field for property $propertyName to be defined and have a type")
                    )
                }
            )
        }
    }

    fun addSetFunction(
        irClass: IrClass,
        propertyName: Name,
        delegate: IrField,
        stmField: IrField,
        setVar: IrFunctionSymbol
    ): IrFunction? {
        val setterFunDescriptor =
            irClass.findMethodDescriptor(StmResolveExtension.setterName(propertyName))
                ?: return null

        return irClass.contributeFunction(setterFunDescriptor, declareNew = true) { f ->
            val stmFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), stmField)

            val stmContextParam = f.valueParameters[0]
            val newValueParameter = f.valueParameters[1]

            val delegateFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), delegate)

            +irInvoke(
                dispatchReceiver = stmFieldExpr,
                callee = setVar,
                args = *arrayOf(irGet(stmContextParam), delegateFieldExpr, irGet(newValueParameter)),
                typeHint = context.irBuiltIns.unitType
            ).apply {
                putTypeArgument(index = 0, type = newValueParameter.type)
            }
        }
    }

    fun addDelegateAndAccessorFunctions(
        irClass: IrClass,
        propertyName: Name,
        backingField: IrField,
        stmField: IrField,
        wrap: IrFunctionSymbol,
        universalDelegateClassSymbol: IrClassSymbol,
        getVar: IrFunctionSymbol,
        setVar: IrFunctionSymbol
    ): List<IrFunction> {
        val delegate =
            addDelegateField(irClass, propertyName, backingField, stmField, wrap, universalDelegateClassSymbol)

        val result = mutableListOf<IrFunction>()

        val getterFun = addGetFunction(irClass, propertyName, delegate, stmField, getVar)
        val setterFun = addSetFunction(irClass, propertyName, delegate, stmField, setVar)

        result += listOfNotNull(getterFun, setterFun)

        return result
    }
}

private fun ClassDescriptor.checkPublishMethodResult(type: KotlinType): Boolean =
    KotlinBuiltIns.isInt(type)

private fun ClassDescriptor.checkPublishMethodParameters(parameters: List<ValueParameterDescriptor>): Boolean =
    parameters.size == 0

class StmLoweringException(override val message: String) : Exception()

open class StmIrGenerator {

    companion object {

        private fun findSTMClassDescriptorOrThrow(module: ModuleDescriptor, symbolTable: SymbolTable, className: Name): ClassDescriptor =
            module.findClassAcrossModuleDependencies(
                ClassId(
                    STM_PACKAGE,
                    className
                )
            )
                ?: throw StmLoweringException("Couldn't find $className runtime class in dependencies of module ${module.name}")

        private fun findSTMContextTypeOrThrow(
            module: ModuleDescriptor,
            symbolTable: SymbolTable
        ): IrType =
            findSTMClassDescriptorOrThrow(module, symbolTable, STM_CONTEXT)
                .let(symbolTable::referenceClass)
                .defaultType

        private fun findMethodDescriptorOrThrow(
            module: ModuleDescriptor,
            classDescriptor: ClassDescriptor,
            methodName: Name
        ): SimpleFunctionDescriptor =
            classDescriptor.findMethods(methodName).firstOrNull()
                ?: throw StmLoweringException(
                    "Couldn't find ${classDescriptor.name}.$methodName(...) runtime method in dependencies of module ${module.name}"
                )

        private fun findSTMMethodDescriptorOrThrow(
            module: ModuleDescriptor,
            symbolTable: SymbolTable,
            className: Name,
            methodName: Name
        ): SimpleFunctionDescriptor = findMethodDescriptorOrThrow(
            module,
            findSTMClassDescriptorOrThrow(module, symbolTable, className),
            methodName
        )

        private fun findSTMMethodIrOrThrow(
            module: ModuleDescriptor,
            symbolTable: SymbolTable,
            className: Name,
            methodName: Name
        ): IrFunctionSymbol =
            symbolTable.referenceSimpleFunction(findSTMMethodDescriptorOrThrow(module, symbolTable, className, methodName))

        private fun getSTMField(irClass: IrClass, symbolTable: SymbolTable): IrField {
            val stmClassSymbol = findSTMClassDescriptorOrThrow(irClass.module, symbolTable, STM_INTERFACE)
                .let(symbolTable::referenceClass)

            val stmType = IrSimpleTypeBuilder().run {
                classifier = stmClassSymbol
                hasQuestionMark = false
                buildSimpleType()
            }

            return irClass.addField {
                name = Name.identifier(STM_FIELD_NAME)
                type = stmType
                visibility = Visibilities.PRIVATE
                origin = IrDeclarationOrigin.DELEGATED_MEMBER
                isFinal = true
                isStatic = false
                buildField()
            }
        }

        private fun getSTMSearchMethod(module: ModuleDescriptor, symbolTable: SymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_SEARCHER, SEARCH_STM_METHOD)

        private fun getSTMWrapMethod(module: ModuleDescriptor, symbolTable: SymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, WRAP_METHOD)

        private fun getSTMGetvarMethod(module: ModuleDescriptor, symbolTable: SymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, GET_VAR_METHOD)

        private fun getSTMSetvarMethod(module: ModuleDescriptor, symbolTable: SymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, SET_VAR_METHOD)

        private fun getSTMSearchClass(module: ModuleDescriptor, symbolTable: SymbolTable): ClassDescriptor =
            findSTMClassDescriptorOrThrow(module, symbolTable, STM_SEARCHER)

        private fun getRunAtomicallyFun(module: ModuleDescriptor, symbolTable: SymbolTable): IrFunctionSymbol =
            findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, RUN_ATOMICALLY_METHOD)

        private fun getSTMContextType(compilerContext: IrPluginContext, module: ModuleDescriptor, symbolTable: SymbolTable): IrType =
            compilerContext.typeTranslator.translateType(
                findSTMMethodIrOrThrow(module, symbolTable, STM_INTERFACE, GET_CONTEXT).descriptor.returnType!!
            )

        fun patchSharedClass(
            irClass: IrClass,
            context: IrPluginContext,
            symbolTable: SymbolTable
        ) {
            val generator = STMGenerator(context)

            val stmField = getSTMField(irClass, symbolTable)
            val stmSearch = getSTMSearchMethod(irClass.module, symbolTable)
            val stmSearchClass = getSTMSearchClass(irClass.module, symbolTable)
            generator.generateSTMField(irClass, stmField, stmSearch, stmSearchClass)

            val universalDelegateClassSymbol = findSTMClassDescriptorOrThrow(irClass.module, symbolTable, UNIVERSAL_DELEGATE)
                .let(symbolTable::referenceClass)
            val stmWrap = getSTMWrapMethod(irClass.module, symbolTable)
            val getVar = getSTMGetvarMethod(irClass.module, symbolTable)
            val setVar = getSTMSetvarMethod(irClass.module, symbolTable)
            val stmContextType = getSTMContextType(context, irClass.module, symbolTable)

            val runAtomically = getRunAtomicallyFun(irClass.module, symbolTable)

            irClass.functions.forEach { f ->
                generator.wrapFunctionIntoTransaction(irClass, f, stmField, runAtomically, stmContextType)
            }


            irClass.transformDeclarationsFlat { p ->
                when (p) {
                    is IrProperty -> {
                        val backingField = p.backingField
                        val pName = p.name

                        if (backingField != null && !pName.isSTMFieldName() && !pName.isSharable())
                            generator.addDelegateAndAccessorFunctions(
                                irClass,
                                pName,
                                backingField,
                                stmField,
                                stmWrap,
                                universalDelegateClassSymbol,
                                getVar,
                                setVar
                            )
                        else
                            null
                    }
                    is IrField -> {
                        val pName = p.name

                        if (pName.isSTMFieldName() || pName.isSharable())
                            null
                        else
                            generator.addDelegateAndAccessorFunctions(
                                irClass,
                                pName,
                                p,
                                stmField,
                                stmWrap,
                                universalDelegateClassSymbol,
                                getVar,
                                setVar
                            )
                    }
                    else -> null
                }
            }
        }

        private fun getSyntheticAccessorForSharedClass(
            module: ModuleDescriptor,
            symbolTable: SymbolTable,
            classDescriptor: ClassDescriptor,
            accessorName: Name
        ): IrFunctionSymbol =
            symbolTable.referenceSimpleFunction(
                findMethodDescriptorOrThrow(
                    module,
                    classDescriptor,
                    accessorName
                )
            )

        private fun getSyntheticGetterForSharedClass(
            module: ModuleDescriptor,
            symbolTable: SymbolTable,
            classDescriptor: ClassDescriptor,
            varName: Name
        ): IrFunctionSymbol =
            getSyntheticAccessorForSharedClass(module, symbolTable, classDescriptor, StmResolveExtension.getterName(varName))


        private fun getSyntheticSetterForSharedClass(
            module: ModuleDescriptor,
            symbolTable: SymbolTable,
            classDescriptor: ClassDescriptor,
            varName: Name
        ): IrFunctionSymbol =
            getSyntheticAccessorForSharedClass(module, symbolTable, classDescriptor, StmResolveExtension.setterName(varName))

        private fun callFunction(
            f: IrFunctionSymbol,
            oldCall: IrCall,
            dispatchReceiver: IrExpression?,
            extensionReceiver: IrExpression?,
            vararg args: IrExpression?
        ): IrCall {
            val newCall = IrCallImpl(
                oldCall.startOffset,
                oldCall.endOffset,
                oldCall.type,
                f,
                oldCall.origin,
                oldCall.superQualifierSymbol
            )

            args.forEachIndexed { index, irExpression -> newCall.putValueArgument(index, irExpression) }

            newCall.dispatchReceiver = dispatchReceiver
            newCall.extensionReceiver = extensionReceiver

            return newCall
        }

        fun patchFunction(
            oldFunction: IrFunction,
            symbolTable: SymbolTable,
            argumentMap: HashMap<IrValueSymbol, IrValueParameter>
        ): IrFunction {
            val oldDescriptor = oldFunction.descriptor

            val newDescriptor = WrappedSimpleFunctionDescriptor()
            val newFunction = IrFunctionImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = STM_PLUGIN_ORIGIN,
                symbol = IrSimpleFunctionSymbolImpl(newDescriptor),
                name = oldFunction.name,
                visibility = Visibilities.LOCAL,
                modality = oldDescriptor.modality,
                returnType = oldFunction.returnType,
                isInline = oldFunction.isInline,
                isExternal = oldFunction.isExternal,
                isTailrec = oldFunction.safeAs<IrSimpleFunction>()?.isTailrec ?: false,
                isSuspend = oldFunction.isSuspend,
                isExpect = oldFunction.isExpect,
                isFakeOverride = false,
                isOperator = false
            ).apply {
                newDescriptor.bind(this)
                parent = oldFunction.parent
                extensionReceiverParameter = oldFunction.extensionReceiverParameter
                dispatchReceiverParameter = oldFunction.dispatchReceiverParameter
                body = oldFunction.body?.deepCopyWithSymbols(initialParent = this)
                valueParameters = oldFunction.valueParameters.map { it.copyTo(this) } + buildValueParameter {
                    type = findSTMContextTypeOrThrow(oldFunction.module, symbolTable)
                    index = oldFunction.valueParameters.size
                    name = "ctx".synthesizedName
                    parent = this@apply
                }
                patchDeclarationParents(this)
            }

            oldFunction.valueParameters.forEachIndexed { i, oldArg ->
                argumentMap[oldArg.symbol] = newFunction.valueParameters[i]
            }

            return newFunction
        }

        private fun fetchStmContext(functionStack: MutableList<IrFunction>, currentFunctionName: Name): IrGetValue =
            fetchStmContextOrNull(functionStack)
                ?: throw StmLoweringException("Call of function $currentFunctionName requires $STM_CONTEXT_CLASS to be present in scope")

        fun patchPropertyAccess(
            irCall: IrCall,
            accessorDescriptor: PropertyAccessorDescriptor,
            functionStack: MutableList<IrFunction>,
            symbolTable: SymbolTable,
            compilerContext: IrPluginContext
        ): IrCall {
            val propertyName = accessorDescriptor.correspondingProperty.name

            if (propertyName.asString().startsWith(STM_FIELD_NAME))
                return irCall

            val dispatchReceiver = irCall.dispatchReceiver?.deepCopyWithVariables()
            val extensionReceiver = irCall.extensionReceiver?.deepCopyWithVariables()
            val classDescriptor = dispatchReceiver?.type?.classOrNull?.descriptor
                ?: extensionReceiver?.type?.classOrNull?.descriptor
                ?: throw StmLoweringException("Unexpected call of setter for an unknown class (setter's descriptor could not be found: $irCall)")

            val contextValue = fetchStmContextOrNull(functionStack)

            fun nullCtx(accessor: IrFunctionSymbol): IrExpression =
                IrConstImpl.constNull(
                    irCall.startOffset,
                    irCall.endOffset,
                    with(STMGenerator(compilerContext)) { accessor.descriptor.valueParameters[0].type.toIrType() }
                )

            return when {
                accessorDescriptor.name.asString().startsWith(KT_DEFAULT_GET_PREFIX) -> {
                    val getter = getSyntheticGetterForSharedClass(accessorDescriptor.module, symbolTable, classDescriptor, propertyName)

                    callFunction(
                        f = getter,
                        oldCall = irCall,
                        dispatchReceiver = dispatchReceiver,
                        extensionReceiver = extensionReceiver,
                        args = *arrayOf(contextValue ?: nullCtx(getter))
                    )
                }
                else -> /* KT_DEFAULT_SET_PREFIX */ {
                    val setter = getSyntheticSetterForSharedClass(accessorDescriptor.module, symbolTable, classDescriptor, propertyName)
                    val newValue = irCall.getValueArgument(0)?.deepCopyWithVariables()

                    callFunction(
                        f = setter,
                        oldCall = irCall,
                        dispatchReceiver = dispatchReceiver,
                        extensionReceiver = extensionReceiver,
                        args = *arrayOf(contextValue ?: nullCtx(setter), newValue)
                    )
                }
            }

        }

        fun patchAtomicFunctionCall(
            irCall: IrCall,
            irFunction: IrFunctionSymbol,
            functionStack: MutableList<IrFunction>,
            funTransformMap: FunctionTransformMap
        ): IrCall {
            val funName = irFunction.descriptor.name
            val contextValue = fetchStmContext(functionStack, currentFunctionName = funName)

            val newFunction = funTransformMap[irFunction]?.symbol
                ?: throw StmLoweringException("Function $funName expected to be mapped to a transformed function")

            val dispatchReceiver = irCall.dispatchReceiver?.deepCopyWithVariables()
            val extensionReceiver = irCall.extensionReceiver?.deepCopyWithVariables()
            val args = (0 until irCall.valueArgumentsCount)
                .map(irCall::getValueArgument)
                .map { it?.deepCopyWithVariables() }
                .toMutableList()

            args += contextValue

            return callFunction(
                f = newFunction,
                oldCall = irCall,
                dispatchReceiver = dispatchReceiver,
                extensionReceiver = extensionReceiver,
                args = *args.toTypedArray()
            )
        }

        fun patchGetUpdatedValue(expression: IrGetValue, newValue: IrValueParameter) = IrGetValueImpl(
            expression.startOffset,
            expression.endOffset,
            expression.type,
            newValue.symbol,
            expression.origin
        )
    }
}

//@Shared
//fun f() {
//
//}

//fun Context.f2() {
//    x.setZ(ctx, 1)
//}