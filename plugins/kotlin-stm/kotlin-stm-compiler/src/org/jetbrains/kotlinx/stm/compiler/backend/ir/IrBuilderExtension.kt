/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler.backend.ir

import org.jetbrains.kotlin.backend.common.extensions.IrPluginContext
import org.jetbrains.kotlin.backend.common.lower.DeclarationIrBuilder
import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrExpression
import org.jetbrains.kotlin.ir.expressions.IrMemberAccessExpression
import org.jetbrains.kotlin.ir.expressions.impl.IrGetObjectValueImpl
import org.jetbrains.kotlin.ir.symbols.IrFunctionSymbol
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.util.patchDeclarationParents
import org.jetbrains.kotlin.ir.util.withScope
import org.jetbrains.kotlin.psi2ir.Psi2IrConfiguration
import org.jetbrains.kotlin.psi2ir.generators.DeclarationGenerator
import org.jetbrains.kotlin.psi2ir.generators.FunctionGenerator
import org.jetbrains.kotlin.psi2ir.generators.GeneratorContext
import org.jetbrains.kotlin.psi2ir.generators.GeneratorExtensions
import org.jetbrains.kotlin.types.KotlinType

abstract class IrBuilderExtension() {
    abstract val compilerContext: IrPluginContext

    private fun IrDeclarationContainer.declareSimpleFunctionWithExternalOverrides(
        descriptor: FunctionDescriptor
    ): IrSimpleFunction {
        val functionSymbol = compilerContext.symbolTable.referenceSimpleFunction(descriptor)
        val function = if (functionSymbol.isBound) functionSymbol.owner else {
            compilerContext.symbolTable.declareSimpleFunction(
                startOffset,
                endOffset,
                STM_PLUGIN_ORIGIN,
                descriptor
            ).also {
                it.parent = this
                declarations.add(it)
            }
        }
        return function.also { f ->
            f.overriddenSymbols = descriptor.overriddenDescriptors.map {
                compilerContext.symbolTable.referenceSimpleFunction(it.original)
            }
        }
    }

    private fun createFunctionGenerator(): FunctionGenerator = with(compilerContext) {
        return FunctionGenerator(
            DeclarationGenerator(
                GeneratorContext(
                    Psi2IrConfiguration(),
                    moduleDescriptor,
                    bindingContext,
                    languageVersionSettings,
                    symbolTable,
                    GeneratorExtensions(),
                    typeTranslator,
                    typeTranslator.constantValueGenerator,
                    irBuiltIns
                )
            )
        )
    }

    fun IrDeclarationContainer.contributeFunction(
        descriptor: FunctionDescriptor,
        declareNew: Boolean = false,
        bodyGen: IrBlockBodyBuilder.(IrFunction) -> Unit
    ): IrSimpleFunction {
        val f: IrSimpleFunction = if (declareNew)
            declareSimpleFunctionWithExternalOverrides(descriptor)
        else
            compilerContext.symbolTable.referenceSimpleFunction(descriptor).owner
        f.buildWithScope {
            if (declareNew)
                createFunctionGenerator().generateFunctionParameterDeclarationsAndReturnType(f, null, null)
            else
                it.returnType = it.descriptor.returnType!!.toIrType()
        }
        f.body = DeclarationIrBuilder(compilerContext, f.symbol, this.startOffset, this.endOffset).irBlockBody(
            this.startOffset,
            this.endOffset
        ) { bodyGen(f) }

        f.patchDeclarationParents(this)

        return f
    }

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