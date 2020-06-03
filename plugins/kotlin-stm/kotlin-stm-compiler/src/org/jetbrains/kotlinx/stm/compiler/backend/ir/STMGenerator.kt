/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.backend.common.lower.parents
import org.jetbrains.kotlin.descriptors.ReceiverParameterDescriptor
import org.jetbrains.kotlin.descriptors.Visibilities
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.builders.declarations.addField
import org.jetbrains.kotlin.ir.builders.declarations.buildField
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrFunctionImpl
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.descriptors.WrappedReceiverParameterDescriptor
import org.jetbrains.kotlin.ir.descriptors.WrappedSimpleFunctionDescriptor
import org.jetbrains.kotlin.ir.expressions.IrReturn
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin
import org.jetbrains.kotlin.ir.expressions.impl.IrFunctionExpressionImpl
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrSimpleFunctionSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrSimpleType
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.impl.IrSimpleTypeBuilder
import org.jetbrains.kotlin.ir.types.impl.buildSimpleType
import org.jetbrains.kotlin.ir.types.impl.makeTypeProjection
import org.jetbrains.kotlin.ir.types.typeOrNull
import org.jetbrains.kotlin.ir.util.deepCopyWithSymbols
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.statements
import org.jetbrains.kotlin.ir.util.substitute
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.types.Variance
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlinx.stm.compiler.SHARABLE_NAME_SUFFIX
import org.jetbrains.kotlinx.stm.compiler.extensions.StmResolveExtension

class STMGenerator(override val compilerContext: IrPluginContext) : IrBuilderExtension() {

    fun generateSTMField(irClass: IrClass, field: IrField, initMethod: IrFunctionSymbol) =
        irClass.initField(field) {
            irCall(initMethod, field.type)
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
        irClass.contributeFunction(irFunction) {

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
                name = "${irFunction.name}_atomicLambda".synthesizedName,
                visibility = Visibilities.LOCAL,
                modality = irFunction.modality,
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
                patchDeclarationParents(irFunction)
            }

            val lambdaType = runAtomically.owner.valueParameters[1].type.substitute(
                runAtomically.owner.typeParameters,
                listOf(funReturnType)
            )

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
                            ?: irNull(runAtomically.owner.valueParameters[0].type),
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
            name = Name.identifier("${propertyName}$SHARABLE_NAME_SUFFIX")
            type = delegateType
            visibility = Visibilities.PRIVATE
            origin = IrDeclarationOrigin.DELEGATED_MEMBER
            isFinal = true
            isStatic = false
            buildField()
        }

        irClass.initField(delegateField) {
            val stmFieldExpr = irGetField(irGet(irClass.thisReceiver!!), stmField)

            val initValue =
                backingField.initializer?.expression?.deepCopyWithSymbols(initialParent = delegateField) ?: irNull(backingField.type)

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

    private fun IrClass.findMethod(name: Name) = this
        .declarations
        .find {
            it is IrFunction
                    && it.name == name
        } as IrFunction

    fun addGetFunction(
        irClass: IrClass,
        propertyName: Name,
        delegate: IrField,
        stmField: IrField,
        getVar: IrFunctionSymbol
    ) {
        val getterFun =
            irClass.findMethod(StmResolveExtension.getterName(propertyName)) ?: return

        irClass.contributeFunction(getterFun) { f ->
            val stmFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), stmField)

            val stmContextParam = f.valueParameters[0]

            val delegateFieldExpr = irGetField(irGet(f.dispatchReceiverParameter!!), delegate)

            f.dispatchReceiverParameter!!.parent = f

            +irReturn(
                irInvoke(
                    dispatchReceiver = stmFieldExpr,
                    callee = getVar,
                    args = *arrayOf(irGet(stmContextParam), delegateFieldExpr),
                    typeHint = getterFun.returnType
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
    ) {
        val setterFun =
            irClass.findMethod(StmResolveExtension.setterName(propertyName)) ?: return

        irClass.contributeFunction(setterFun) { f ->
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
        setVar: IrFunctionSymbol,
        oldDeclaration: IrDeclaration
    ) {
        val delegate =
            addDelegateField(irClass, propertyName, backingField, stmField, wrap, universalDelegateClassSymbol)

        addGetFunction(irClass, propertyName, delegate, stmField, getVar)
        addSetFunction(irClass, propertyName, delegate, stmField, setVar)

        irClass.declarations -= oldDeclaration
    }
}