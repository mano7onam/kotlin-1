/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.codeInsight.hints

import com.intellij.codeInsight.hints.*
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.psi.PsiElement
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.parameterInfo.*
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameOrNull
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

@Suppress("UnstableApiUsage")
class KotlinInlayParameterHintsProvider : InlayParameterHintsProvider {

    override fun getSupportedOptions(): List<Option> = listOf(HintType.PARAMETER_HINT.option)

    override fun getDefaultBlackList(): Set<String> =
        setOf(
            "*listOf", "*setOf", "*arrayOf", "*ListOf", "*SetOf", "*ArrayOf", "*assert*(*)", "*mapOf", "*MapOf",
            "kotlin.require*(*)", "kotlin.check*(*)", "*contains*(value)", "*containsKey(key)", "kotlin.lazyOf(value)",
            "*SequenceBuilder.resume(value)", "*SequenceBuilder.yield(value)"
        )

    override fun getHintInfo(element: PsiElement): HintInfo? {
        return when (val hintType = HintType.resolve(element) ?: return null) {
            HintType.PARAMETER_HINT -> {
                val parent = (element as? KtValueArgumentList)?.parent
                (parent as? KtCallElement)?.let { getMethodInfo(it) }
            }
            else -> HintInfo.OptionInfo(hintType.option)
        }
    }

    override fun getParameterHints(element: PsiElement): List<InlayInfo> {
        val resolveToEnabled = HintType.resolveToEnabled(element) ?: return emptyList()
        return resolveToEnabled.provideHints(element)
    }

    override fun getBlackListDependencyLanguage(): Language = JavaLanguage.INSTANCE

    override fun getInlayPresentation(inlayText: String): String =
        if (inlayText.startsWith(TYPE_INFO_PREFIX)) {
            inlayText.substring(TYPE_INFO_PREFIX.length)
        } else {
            super.getInlayPresentation(inlayText)
        }

    private fun getMethodInfo(elem: KtCallElement): HintInfo.MethodInfo? {
        val resolvedCall = elem.resolveToCall()
        val resolvedCallee = resolvedCall?.candidateDescriptor
        if (resolvedCallee is FunctionDescriptor) {
            val paramNames =
                resolvedCallee.valueParameters.asSequence().map { it.name }.filter { !it.isSpecial }.map(Name::asString).toList()
            val fqName = if (resolvedCallee is ConstructorDescriptor)
                resolvedCallee.containingDeclaration.fqNameSafe.asString()
            else
                (resolvedCallee.fqNameOrNull()?.asString() ?: return null)
            return HintInfo.MethodInfo(fqName, paramNames)
        }
        return null
    }

    override fun getMainCheckboxText(): String {
        return KotlinBundle.message("hints.settings.common.items")
    }
}

fun PsiElement.isNameReferenceInCall() =
    this is KtNameReferenceExpression && parent is KtCallExpression