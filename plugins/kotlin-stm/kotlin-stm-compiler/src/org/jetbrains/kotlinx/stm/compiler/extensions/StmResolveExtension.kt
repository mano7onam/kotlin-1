/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler.extensions

import org.jetbrains.kotlin.backend.common.descriptors.synthesizedName
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.AnnotationDescriptor
import org.jetbrains.kotlin.descriptors.annotations.Annotations
import org.jetbrains.kotlin.descriptors.annotations.createDeprecatedAnnotation
import org.jetbrains.kotlin.descriptors.impl.SimpleFunctionDescriptorImpl
import org.jetbrains.kotlin.descriptors.impl.ValueParameterDescriptorImpl
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.name.Name.identifier
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.descriptorUtil.module
import org.jetbrains.kotlin.resolve.extensions.SyntheticResolveExtension
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlinx.stm.compiler.*


open class StmResolveExtension : SyntheticResolveExtension {

    companion object {

        internal fun getterName(name: Name) =
            identifier("$GET_PREFIX$name$SHARABLE_NAME_SUFFIX")

        internal fun setterName(name: Name) =
            identifier("$SET_PREFIX$name$SHARABLE_NAME_SUFFIX")

        internal fun undoGetterName(name: Name) =
            identifier(name.asString().removePrefix(GET_PREFIX).removeSuffix(SHARABLE_NAME_SUFFIX))

        internal fun undoSetterName(name: Name) =
            identifier(name.asString().removePrefix(SET_PREFIX).removeSuffix(SHARABLE_NAME_SUFFIX))

        private fun createDeprecatedHiddenAnnotation(module: ModuleDescriptor): AnnotationDescriptor {
            return module.builtIns.createDeprecatedAnnotation(
                "This synthesized declaration should not be used directly",
                level = "HIDDEN"
            )
        }

        fun createAndInitFunctionDescriptor(
            containingDeclaration: DeclarationDescriptor,
            sourceElement: SourceElement,
            functionName: Name,
            dispatchReceiverParameter: ReceiverParameterDescriptor?,
            extensionReceiverParameter: ReceiverParameterDescriptor?,
            type: KotlinType,
            visibility: Visibility,
            typeParameters: List<TypeParameterDescriptor> = listOf(),
            valueParametersBuilder: (SimpleFunctionDescriptor) -> List<ValueParameterDescriptor>
        ): SimpleFunctionDescriptor {
            val descriptor = SimpleFunctionDescriptorImpl.create(
                containingDeclaration,
                Annotations.create(listOf(createDeprecatedHiddenAnnotation(containingDeclaration.module))),
                functionName,
                CallableMemberDescriptor.Kind.SYNTHESIZED,
                sourceElement
            )

            val valueParameters = valueParametersBuilder(descriptor)

            descriptor.initialize(
                extensionReceiverParameter,
                dispatchReceiverParameter,
                listOf(),
                valueParameters,
                type,
                Modality.FINAL,
                visibility
            )

            return descriptor
        }

        fun createValueParam(
            sourceElement: SourceElement,
            containingFunction: FunctionDescriptor,
            type: KotlinType,
            name: String,
            index: Int
        ) =
            ValueParameterDescriptorImpl(
                containingFunction,
                original = null,
                index = index,
                annotations = Annotations.EMPTY,
                name = identifier(name),
                outType = type,
                declaresDefaultValue = false,
                isCrossinline = false,
                isNoinline = false,
                varargElementType = null,
                source = sourceElement
            )

        internal fun findSTMContextClass(module: ModuleDescriptor) = requireNotNull(
            module.findClassAcrossModuleDependencies(
                ClassId(
                    STM_PACKAGE,
                    STM_CONTEXT
                )
            )
        ) { "Couldn't find $STM_CONTEXT runtime class in dependencies of module ${module.name}" }

        fun createContextValueParam(
            module: ModuleDescriptor,
            sourceElement: SourceElement,
            containingFunction: FunctionDescriptor,
            index: Int,
            nullable: Boolean = false
        ) =
            createValueParam(
                sourceElement,
                containingFunction,
                findSTMContextClass(module).defaultType.let {
                    if (nullable) it.makeNullableAsSpecified(true)
                    else it
                },
                name = "ctx".synthesizedName.identifier,
                index = index
            )
    }

    override fun getSyntheticFunctionNames(thisDescriptor: ClassDescriptor): List<Name> = when {
        thisDescriptor.annotations.hasAnnotation(SHARED_MUTABLE_ANNOTATION) ->
            thisDescriptor.unsubstitutedMemberScope.getVariableNames()
                .filter { thisDescriptor.unsubstitutedMemberScope.getContributedVariables(it, NoLookupLocation.FROM_BACKEND).isNotEmpty() }
                .map { listOf(getterName(it), setterName(it)) }
                .flatten()
        else -> listOf()
    }


    override fun generateSyntheticMethods(
        thisDescriptor: ClassDescriptor,
        name: Name,
        bindingContext: BindingContext,
        fromSupertypes: List<SimpleFunctionDescriptor>,
        result: MutableCollection<SimpleFunctionDescriptor>
    ) {
        if (!name.asString().startsWith(GET_PREFIX) && !name.asString().startsWith(SET_PREFIX))
            return
        val varName = undoSetterName(undoGetterName(name))

        val property = thisDescriptor.unsubstitutedMemberScope.getContributedVariables(
            varName,
            NoLookupLocation.FROM_BACKEND
        ).first()

        if (name.asString().startsWith(GET_PREFIX)) {
            val newGetter = createAndInitFunctionDescriptor(
                containingDeclaration = thisDescriptor,
                sourceElement = thisDescriptor.source,
                functionName = getterName(varName),
                dispatchReceiverParameter = thisDescriptor.thisAsReceiverParameter,
                extensionReceiverParameter = null,
                type = property.type,
                visibility = property.visibility
            ) { newGetter ->
                listOf(createContextValueParam(thisDescriptor.module, thisDescriptor.source, newGetter, index = 0, nullable = true))
            }

            result += newGetter
        } else if (name.asString().startsWith(SET_PREFIX)) {
            val newSetter = createAndInitFunctionDescriptor(
                containingDeclaration = thisDescriptor,
                sourceElement = thisDescriptor.source,
                functionName = setterName(varName),
                dispatchReceiverParameter = thisDescriptor.thisAsReceiverParameter,
                extensionReceiverParameter = null,
                type = thisDescriptor.module.builtIns.unitType,
                visibility = property.visibility
            ) { newSetter ->
                listOf(
                    createContextValueParam(thisDescriptor.module, thisDescriptor.source, newSetter, index = 0, nullable = true),
                    createValueParam(thisDescriptor.source, newSetter, property.type, name = "newValue", index = 1)
                )
            }

            result += newSetter
        }

    }
}