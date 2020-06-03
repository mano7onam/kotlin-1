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
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.builders.declarations.buildValueParameter
import org.jetbrains.kotlin.ir.builders.irCall
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.expressions.impl.IrCallImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrGetValueImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classOrNull
import org.jetbrains.kotlin.ir.types.defaultType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeBuilder
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.isClassWithFqName
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.ir.visitors.acceptVoid
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.platform.isCommon
import org.jetbrains.kotlin.platform.isMultiPlatform
import org.jetbrains.kotlin.platform.js.isJs
import org.jetbrains.kotlin.platform.jvm.isJvm
import org.jetbrains.kotlin.utils.addToStdlib.firstNotNullResult
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlinx.stm.compiler.*
import org.jetbrains.kotlinx.stm.compiler.extensions.FunctionTransformMap
import org.jetbrains.kotlinx.stm.compiler.extensions.StmResolveExtension
import org.jetbrains.kotlinx.stm.compiler.extensions.correspondingProperty
import kotlin.collections.set

// Is creating synthetic origin is a good idea or not?
object STM_PLUGIN_ORIGIN : IrDeclarationOriginImpl("STM")

val BackendContext.externalSymbols: ReferenceSymbolTable get() = ir.symbols.externalSymbolTable

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

class StmLoweringException(override val message: String) : Exception()

open class StmIrGenerator {

    companion object {

        private fun findSTMClassSymbolOrThrow(
            context: IrPluginContext,
            className: Name
        ) = context.referenceClass(STM_PACKAGE.child(className))
            ?: throw StmLoweringException("Couldn't find $className runtime class in dependencies")

        private fun findSTMContextTypeOrThrow(
            context: IrPluginContext
        ): IrType = findSTMClassSymbolOrThrow(context, STM_CONTEXT).defaultType

        private fun findMethodSymbolOrThrow(
            context: IrPluginContext,
            className: Name,
            methodName: String
        ) = findSTMClassSymbolOrThrow(context, className).getSimpleFunction(methodName)
            ?: throw StmLoweringException(
                "Couldn't find ${className}.$methodName(...) runtime method in dependencies"
            )

        private fun getSTMField(irClass: IrClass, context: IrPluginContext): IrField {
            val stmClassSymbol = findSTMClassSymbolOrThrow(context, STM_INTERFACE)

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

        private fun getSTMSearchFunction(
            module: ModuleDescriptor,
            compilerContext: IrPluginContext
        ): IrFunctionSymbol {
            val methodBaseName = when {
                module.platform.isJs() -> SEARCH_JS_STM_METHOD
                module.platform.isJvm() -> SEARCH_JAVA_STM_METHOD
                module.platform.isMultiPlatform() -> error("Unexpected platform in IR code: Multiplatform")
                module.platform.isCommon() -> error("Unexpected platform in IR code: Common")
                else -> SEARCH_NATIVE_STM_METHOD
            }
            val methodDefaultName = Name.identifier("$methodBaseName$DEFAULT_SUFFIX")

            return compilerContext.referenceFunctions(STM_PACKAGE.child(methodBaseName)).singleOrNull()
                ?: compilerContext.referenceFunctions(STM_PACKAGE.child(methodDefaultName)).singleOrNull()
                ?: throw StmLoweringException("Expected $methodDefaultName to be visible in module ${module.name}")
        }

        private fun getRunAtomicallyFunction(
            module: ModuleDescriptor,
            compilerContext: IrPluginContext
        ): IrFunctionSymbol =
            compilerContext.referenceFunctions(STM_PACKAGE.child(RUN_ATOMICALLY_GLOBAL_FUNCTION))
                .find { it.owner.valueParameters.size == 2 }
                ?: throw StmLoweringException("Expected $RUN_ATOMICALLY_GLOBAL_FUNCTION to be visible in module ${module.name}")

        private fun getSTMWrapMethod(context: IrPluginContext): IrFunctionSymbol =
            findMethodSymbolOrThrow(context, STM_INTERFACE, WRAP_METHOD)

        private fun getSTMGetvarMethod(context: IrPluginContext): IrFunctionSymbol =
            findMethodSymbolOrThrow(context, STM_INTERFACE, GET_VAR_METHOD)

        private fun getSTMSetvarMethod(context: IrPluginContext): IrFunctionSymbol =
            findMethodSymbolOrThrow(context, STM_INTERFACE, SET_VAR_METHOD)

        private fun getRunAtomicallyFun(context: IrPluginContext): IrFunctionSymbol =
            findMethodSymbolOrThrow(context, STM_INTERFACE, RUN_ATOMICALLY_METHOD)

        private fun getSTMContextType(
            context: IrPluginContext,
        ): IrType = findMethodSymbolOrThrow(context, STM_INTERFACE, GET_CONTEXT).owner.returnType

        fun patchSharedClass(
            irClass: IrClass,
            context: IrPluginContext
        ) {
            val generator = STMGenerator(context)

            val stmField = getSTMField(irClass, context)
            val stmSearch = getSTMSearchFunction(irClass.module, context)

            generator.generateSTMField(irClass, stmField, stmSearch)

            val universalDelegateClassSymbol = findSTMClassSymbolOrThrow(context, UNIVERSAL_DELEGATE)
            val stmWrap = getSTMWrapMethod(context)
            val getVar = getSTMGetvarMethod(context)
            val setVar = getSTMSetvarMethod(context)
            val stmContextType = getSTMContextType(context)

            val runAtomically = getRunAtomicallyFun(context)

            irClass.functions.forEach { f ->
                generator.wrapFunctionIntoTransaction(irClass, f, stmField, runAtomically, stmContextType)
            }

            val oldDeclarations = mutableListOf<IrDeclaration>()
            irClass.declarations.forEach { oldDeclarations.add(it) }

            oldDeclarations.forEach { p ->
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
                                setVar,
                                p
                            )
                    }
                    is IrField -> {
                        val pName = p.name

                        if (!pName.isSTMFieldName() && !pName.isSharable())
                            generator.addDelegateAndAccessorFunctions(
                                irClass,
                                pName,
                                p,
                                stmField,
                                stmWrap,
                                universalDelegateClassSymbol,
                                getVar,
                                setVar,
                                p
                            )
                    }
                }
            }
        }

        private fun getSyntheticAccessorForSharedClass(
            classSymbol: IrClassSymbol,
            accessorName: Name
        ): IrFunctionSymbol = classSymbol.getSimpleFunction(accessorName.asString())
            ?: throw IllegalStateException("$accessorName(...) must be present in ${classSymbol.owner.name}")

        private fun getSyntheticGetterForSharedClass(
            classSymbol: IrClassSymbol,
            varName: Name
        ): IrFunctionSymbol =
            getSyntheticAccessorForSharedClass(classSymbol, StmResolveExtension.getterName(varName))


        private fun getSyntheticSetterForSharedClass(
            classSymbol: IrClassSymbol,
            varName: Name
        ): IrFunctionSymbol =
            getSyntheticAccessorForSharedClass(classSymbol, StmResolveExtension.setterName(varName))

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
            context: IrPluginContext,
            argumentMap: HashMap<IrValueSymbol, IrValueParameter>
        ): IrFunction {
            val newDescriptor = WrappedSimpleFunctionDescriptor()
            val newFunction = IrFunctionImpl(
                startOffset = UNDEFINED_OFFSET,
                endOffset = UNDEFINED_OFFSET,
                origin = STM_PLUGIN_ORIGIN,
                symbol = IrSimpleFunctionSymbolImpl(newDescriptor),
                name = oldFunction.name,
                visibility = Visibilities.LOCAL,
                modality = oldFunction.descriptor.modality,
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
                extensionReceiverParameter = oldFunction.extensionReceiverParameter
                dispatchReceiverParameter = oldFunction.dispatchReceiverParameter
                body = oldFunction.body?.deepCopyWithSymbols(initialParent = this)
                valueParameters = oldFunction.valueParameters.map { it.copyTo(this) } + buildValueParameter {
                    type = findSTMContextTypeOrThrow(context)
                    index = oldFunction.valueParameters.size
                    name = "ctx".synthesizedName
                    parent = this@apply
                }
                patchDeclarationParents(oldFunction.parent)
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
            accessor: IrFunction,
            functionStack: MutableList<IrFunction>
        ): IrCall {
            val propertyName = accessor.correspondingProperty?.name ?: return irCall

            if (propertyName.asString().startsWith(STM_FIELD_NAME))
                return irCall

            val dispatchReceiver = irCall.dispatchReceiver?.deepCopyWithVariables()
            val extensionReceiver = irCall.extensionReceiver?.deepCopyWithVariables()
            val classSymbol = dispatchReceiver?.type?.classOrNull
                ?: extensionReceiver?.type?.classOrNull
                ?: throw StmLoweringException("Unexpected call of setter for an unknown class (setter's descriptor could not be found: $irCall)")

            val contextValue = fetchStmContextOrNull(functionStack)

            fun nullCtx(accessor: IrFunctionSymbol): IrExpression =
                IrConstImpl.constNull(
                    irCall.startOffset,
                    irCall.endOffset,
                    accessor.owner.valueParameters[0].type
                )

            return when {
                accessor.isGetter -> {
                    val getter = getSyntheticGetterForSharedClass(classSymbol, propertyName)

                    callFunction(
                        f = getter,
                        oldCall = irCall,
                        dispatchReceiver = dispatchReceiver,
                        extensionReceiver = extensionReceiver,
                        args = *arrayOf(contextValue ?: nullCtx(getter))
                    )
                }
                accessor.isSetter -> {
                    val setter = getSyntheticSetterForSharedClass(classSymbol, propertyName)
                    val newValue = irCall.getValueArgument(0)?.deepCopyWithVariables()

                    callFunction(
                        f = setter,
                        oldCall = irCall,
                        dispatchReceiver = dispatchReceiver,
                        extensionReceiver = extensionReceiver,
                        args = *arrayOf(contextValue ?: nullCtx(setter), newValue)
                    )
                }
                else -> error("Accessor must be either getter or setter (accessor name: ${accessor.name})")
            }

        }

        fun patchAtomicFunctionCall(
            irCall: IrCall,
            irFunction: IrFunctionSymbol,
            functionStack: MutableList<IrFunction>,
            funTransformMap: FunctionTransformMap
        ): IrCall {
            val funName = irFunction.owner.name
            val contextValue = fetchStmContext(functionStack, currentFunctionName = funName)

            val newFunction = funTransformMap[irFunction]?.symbol
                ?: throw StmLoweringException("Function $funName expected to be mapped to a transformed function")

            val dispatchReceiver = irCall.dispatchReceiver?.deepCopyWithVariables()
            val extensionReceiver = irCall.extensionReceiver?.deepCopyWithVariables()
            val args = (0 until irCall.valueArgumentsCount)
                .map(irCall::getValueArgument)
                .map { it?.deepCopyWithSymbolsAndParent() }
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


        private class ParentSearcherVisitor : IrElementVisitorVoid {
            var result: IrDeclarationParent? = null
            override fun visitDeclaration(declaration: IrDeclaration) {
                result = declaration.parent
            }

            override fun visitElement(element: IrElement) = element.acceptChildrenVoid(this)

        }

        private fun IrElement.findParent(): IrDeclarationParent? = ParentSearcherVisitor().let {
            this.acceptVoid(it)
            it.result
        }

        private inline fun <reified T : IrElement> T.deepCopyWithSymbolsAndParent(): T =
            this.deepCopyWithSymbols(initialParent = this.findParent())

        fun patchRunAtomicallyCall(
            irCall: IrCall,
            irFunctionSymbol: IrFunctionSymbol,
            context: IrPluginContext
        ): IrCall {
            val irFunction = irFunctionSymbol.owner
            val newFunction = getRunAtomicallyFunction(irFunction.module, context)

            val block = irCall.getValueArgument(0)?.deepCopyWithSymbolsAndParent()

            val stmType = newFunction.owner.valueParameters[0].type
            val stmSearch = getSTMSearchFunction(irFunction.module, context)
            val stm = with(DeclarationIrBuilder(context, irFunctionSymbol, irCall.startOffset, irCall.endOffset)) {
                irCall(stmSearch, stmType)
            }

            val res = callFunction(
                f = newFunction,
                oldCall = irCall,
                dispatchReceiver = irCall.dispatchReceiver,
                extensionReceiver = irCall.extensionReceiver,
                args = *arrayOf(stm, block)
            )

            return res
        }
    }
}