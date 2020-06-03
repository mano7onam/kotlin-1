/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlinx.stm.compiler

import org.jetbrains.kotlin.descriptors.ClassDescriptor
import org.jetbrains.kotlin.descriptors.SimpleFunctionDescriptor
import org.jetbrains.kotlin.incremental.components.NoLookupLocation
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

internal val STM_PACKAGE = FqName("kotlinx.stm")

internal val STM_INTERFACE = Name.identifier("STM")

internal val STM_CONTEXT = Name.identifier("STMContext")

internal val UNIVERSAL_DELEGATE = Name.identifier("UniversalDelegate")

internal val SEARCH_JAVA_STM_METHOD = Name.identifier("findJavaSTM")
internal val SEARCH_JS_STM_METHOD = Name.identifier("findJsSTM")
internal val SEARCH_NATIVE_STM_METHOD = Name.identifier("findNativeSTM")
internal val DEFAULT_SUFFIX = Name.identifier("Default")

internal val GET_CONTEXT = "getContext"

internal val WRAP_METHOD = "wrap"

internal val GET_VAR_METHOD = "getVar"

internal val SET_VAR_METHOD = "setVar"

internal val RUN_ATOMICALLY_METHOD = "runAtomically"

internal val RUN_ATOMICALLY_GLOBAL_FUNCTION = Name.identifier("runAtomically")

internal val SHARED_MUTABLE_ANNOTATION = FqName("$STM_PACKAGE.SharedMutable")

internal val ATOMIC_FUNCTION_ANNOTATION_NAME = Name.identifier("AtomicFunction")

internal val ATOMIC_FUNCTION_ANNOTATION = FqName("$STM_PACKAGE.${ATOMIC_FUNCTION_ANNOTATION_NAME.identifier}")

internal val TEMPORARY_IR_FUNCTION_ANNOTATION = FqName("$STM_PACKAGE.TemporaryIrFunction")

internal val STM_CONTEXT_CLASS = FqName("$STM_PACKAGE.${STM_CONTEXT.identifier}")

internal val SHARABLE_NAME_SUFFIX = "\$_______Sharable____"

internal val STM_FIELD_NAME = "_______stm_____"

fun Name.isSharable() = this.asString().endsWith(SHARABLE_NAME_SUFFIX)
fun Name.isSTMFieldName() = this.asString().startsWith(STM_FIELD_NAME)

const val GET_PREFIX = "_____get_"
const val SET_PREFIX = "_set_____"

internal fun ClassDescriptor.findMethods(name: Name): List<SimpleFunctionDescriptor> =
    this.unsubstitutedMemberScope
        .getContributedFunctions(name, NoLookupLocation.FROM_BACKEND)
        .filter { it.name == name }