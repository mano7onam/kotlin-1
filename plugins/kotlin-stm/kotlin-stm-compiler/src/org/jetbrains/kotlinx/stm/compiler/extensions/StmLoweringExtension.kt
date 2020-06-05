/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler.extensions

import org.jetbrains.kotlin.backend.common.extensions.IrGenerationExtension
import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrGetValue
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.symbols.IrValueSymbol
import org.jetbrains.kotlin.ir.util.hasAnnotation
import org.jetbrains.kotlin.ir.util.isAccessor
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.utils.addToStdlib.safeAs
import org.jetbrains.kotlinx.stm.compiler.ATOMIC_FUNCTION_ANNOTATION
import org.jetbrains.kotlinx.stm.compiler.RUN_ATOMICALLY_METHOD
import org.jetbrains.kotlinx.stm.compiler.SHARED_MUTABLE_ANNOTATION
import org.jetbrains.kotlinx.stm.compiler.REPLACEABLE_ATOMIC_FUNCTION_ANNOTATION
import org.jetbrains.kotlinx.stm.compiler.backend.ir.StmIrGenerator

private fun IrFunction.isAtomicFunction() = this.annotations.hasAnnotation(ATOMIC_FUNCTION_ANNOTATION)
private fun IrFunctionSymbol.isAtomicFunction() = this.owner.isAtomicFunction()

private fun IrFunction.isRunAtomically() = this.annotations.hasAnnotation(REPLACEABLE_ATOMIC_FUNCTION_ANNOTATION)
        && this.name.asString() == RUN_ATOMICALLY_METHOD

private fun IrClass.isSharedClass() = this.annotations.hasAnnotation(SHARED_MUTABLE_ANNOTATION)

val IrFunction.correspondingProperty: IrProperty?
    get() = this.safeAs<IrSimpleFunction>()?.correspondingPropertySymbol?.owner

internal typealias FunctionTransformMap = MutableMap<IrFunctionSymbol, IrFunction>

private class StmSharedClassLowering(
    val pluginContext: IrPluginContext
) : IrElementTransformerVoid() {

    override fun visitClass(declaration: IrClass): IrStatement {
        if (declaration.isSharedClass())
            StmIrGenerator.patchSharedClass(
                declaration,
                pluginContext
            )

        declaration.transformChildrenVoid()

        return declaration
    }
}

private class StmAtomicFunctionLowering(
    val pluginContext: IrPluginContext,
    val resultMap: FunctionTransformMap
) : IrElementTransformerVoid() {

    private val argumentMap = hashMapOf<IrValueSymbol, IrValueParameter>()

    override fun visitFunction(declaration: IrFunction): IrStatement {
        val result = if (declaration.isAtomicFunction())
            StmIrGenerator.patchFunction(declaration, pluginContext, argumentMap).also {
                resultMap[declaration.symbol] = it
            }
        else
            declaration

        result.transformChildrenVoid(this)

        return result
    }

    override fun visitGetValue(expression: IrGetValue): IrExpression {
        expression.transformChildrenVoid(this)

        return argumentMap[expression.symbol]?.let { StmIrGenerator.patchGetUpdatedValue(expression, it) } ?: expression
    }

}

private class StmCallLowering(
    val pluginContext: IrPluginContext,
    val funTransformMap: FunctionTransformMap
) : IrElementTransformerVoid() {

    private val functionStack = mutableListOf<IrFunction>()

    override fun visitFunction(declaration: IrFunction): IrStatement {
        functionStack += declaration
        declaration.transformChildrenVoid(this)
        functionStack.pop()

        return declaration
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        val callee = expression.symbol.owner
        val containingDecl = callee.parent

        return when {
            containingDecl is IrClass
                    && containingDecl.isSharedClass()
                    && callee.isAccessor -> StmIrGenerator.patchPropertyAccess(
                expression,
                callee,
                functionStack
            )
            callee.isAtomicFunction() -> StmIrGenerator.patchAtomicFunctionCall(
                expression,
                expression.symbol,
                functionStack,
                funTransformMap
            )
            callee.isRunAtomically() -> StmIrGenerator.patchRunAtomicallyCall(
                expression,
                expression.symbol,
                pluginContext
            )
            else -> expression
        }
    }

}

open class StmLoweringExtension : IrGenerationExtension {
    val funTransformMap: FunctionTransformMap = mutableMapOf()

    override fun generate(
        moduleFragment: IrModuleFragment,
        pluginContext: IrPluginContext
    ) {
        val stmFunctionLowering = StmAtomicFunctionLowering(pluginContext, funTransformMap)
        val stmClassLowering = StmSharedClassLowering(pluginContext)
        val stmCallLowering = StmCallLowering(pluginContext, funTransformMap)

        // apply in order:
        arrayOf(stmFunctionLowering, stmClassLowering, stmCallLowering).forEach { lowering ->
            moduleFragment.transformChildrenVoid(lowering)
        }
    }
}