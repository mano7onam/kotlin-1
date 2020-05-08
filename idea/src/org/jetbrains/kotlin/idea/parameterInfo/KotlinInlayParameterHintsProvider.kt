/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.parameterInfo

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.InsetPresentation
import com.intellij.codeInsight.hints.presentation.MenuOnClickPresentation
import com.intellij.codeInsight.hints.settings.InlayHintsConfigurable
import com.intellij.lang.Language
import com.intellij.lang.java.JavaLanguage
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.layout.panel
import org.jetbrains.kotlin.descriptors.ConstructorDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.caches.resolve.resolveToCall
import org.jetbrains.kotlin.idea.quickfix.createFromUsage.callableBuilder.getReturnTypeReference
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getStrictParentOfType
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameOrNull
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe
import javax.swing.JComponent

enum class HintType(val showDesc: String, val doNotShowDesc: String, defaultEnabled: Boolean) {

    PROPERTY_HINT(
        KotlinBundle.message("hints.title.property.type.enabled"),
        KotlinBundle.message("hints.title.property.type.disabled"),
        false
    ) {
        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            return providePropertyTypeHint(elem)
        }

        override fun isApplicable(elem: PsiElement): Boolean = elem is KtProperty && elem.getReturnTypeReference() == null && !elem.isLocal
    },

    LOCAL_VARIABLE_HINT(
        KotlinBundle.message("hints.title.locals.type.enabled"),
        KotlinBundle.message("hints.title.locals.type.disabled"),
        false
    ) {
        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            return providePropertyTypeHint(elem)
        }

        override fun isApplicable(elem: PsiElement): Boolean =
            (elem is KtProperty && elem.getReturnTypeReference() == null && elem.isLocal) ||
                    (elem is KtParameter && elem.isLoopParameter && elem.typeReference == null) ||
                    (elem is KtDestructuringDeclarationEntry && elem.getReturnTypeReference() == null)
    },

    FUNCTION_HINT(
        KotlinBundle.message("hints.title.function.type.enabled"),
        KotlinBundle.message("hints.title.function.type.disabled"),
        false
    ) {
        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            (elem as? KtNamedFunction)?.let { namedFunc ->
                namedFunc.valueParameterList?.let { paramList ->
                    return provideTypeHint(namedFunc, paramList.endOffset)
                }
            }
            return emptyList()
        }

        override fun isApplicable(elem: PsiElement): Boolean =
            elem is KtNamedFunction && !(elem.hasBlockBody() || elem.hasDeclaredReturnType())
    },

    PARAMETER_TYPE_HINT(
        KotlinBundle.message("hints.title.parameter.type.enabled"),
        KotlinBundle.message("hints.title.parameter.type.disabled"),
        false
    ) {
        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            (elem as? KtParameter)?.let { param ->
                param.nameIdentifier?.let { ident ->
                    return provideTypeHint(param, ident.endOffset)
                }
            }
            return emptyList()
        }

        override fun isApplicable(elem: PsiElement): Boolean = elem is KtParameter && elem.typeReference == null && !elem.isLoopParameter
    },

    PARAMETER_HINT(
        KotlinBundle.message("hints.title.argument.name.enabled"),
        KotlinBundle.message("hints.title.argument.name.disabled"),
        true
    ) {
        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            val callElement = elem.getStrictParentOfType<KtCallElement>() ?: return emptyList()
            return provideArgumentNameHints(callElement)
        }

        override fun isApplicable(elem: PsiElement): Boolean = elem is KtValueArgumentList
    },

    LAMBDA_RETURN_EXPRESSION(
        KotlinBundle.message("hints.title.return.expression.enabled"),
        KotlinBundle.message("hints.title.return.expression.disabled"),
        true
    ) {
        override fun isApplicable(elem: PsiElement) =
            elem is KtExpression && elem !is KtFunctionLiteral && !elem.isNameReferenceInCall()

        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            // Will be painted with ReturnHintLinePainter

            // Enable/Disable setting will be present in the list with other hints.
            // Enable action will be provided by the platform.
            // Disable action need to be reimplemented as hints are not actually added, see DisableReturnLambdaHintOptionAction.

            return emptyList()
        }
    },

    LAMBDA_IMPLICIT_PARAMETER_RECEIVER(
        KotlinBundle.message("hints.title.implicit.parameters.enabled"),
        KotlinBundle.message("hints.title.implicit.parameters.disabled"),
        true
    ) {
        override fun isApplicable(elem: PsiElement) = elem is KtFunctionLiteral

        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            ((elem as? KtFunctionLiteral)?.parent as? KtLambdaExpression)?.let {
                return provideLambdaImplicitHints(it)
            }
            return emptyList()
        }
    },

    SUSPENDING_CALL(
        KotlinBundle.message("hints.title.suspend.calls.enabled"),
        KotlinBundle.message("hints.title.suspend.calls.disabled"),
        false
    ) {
        override fun isApplicable(elem: PsiElement) = elem.isNameReferenceInCall() && ApplicationManager.getApplication().isInternal

        override fun provideHints(elem: PsiElement): List<InlayInfo> {
            val callExpression = elem.parent as? KtCallExpression ?: return emptyList()
            return provideSuspendingCallHint(callExpression)?.let { listOf(it) } ?: emptyList()
        }
    };

    companion object {
        fun resolve(elem: PsiElement): HintType? {
            val applicableTypes = values().filter { it.isApplicable(elem) }
            return applicableTypes.firstOrNull()
        }

        fun resolveToEnabled(elem: PsiElement?): HintType? {

            val resolved = elem?.let { resolve(it) } ?: return null
            return if (resolved.enabled) {
                resolved
            } else {
                null
            }
        }
    }

    abstract fun isApplicable(elem: PsiElement): Boolean
    abstract fun provideHints(elem: PsiElement): List<InlayInfo>
    val option = Option("SHOW_${this.name}", this.showDesc, defaultEnabled)
    val enabled
        get() = option.get()
}

@Suppress("UnstableApiUsage")
class KotlinInlayParameterHintsProvider : InlayParameterHintsProvider {

    override fun getSupportedOptions(): List<Option> = HintType.values().map { it.option }

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
        return KotlinBundle.message("hints.title.parameter")
    }
}

fun PsiElement.isNameReferenceInCall() =
    this is KtNameReferenceExpression && parent is KtCallExpression


@Suppress("UnstableApiUsage")
class KotlinParameterNameHintsProvider : InlayHintsProvider<KotlinParameterNameHintsProvider.Settings> {

    data class Settings(
        var parameterNames: Boolean = false
    )

    override val key: SettingsKey<Settings> = SettingsKey("KotlinParameterNamesHints")
    override val name: String = "Parameter names"
    override val previewText: String? = ""

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = panel {}

            override val mainCheckboxText: String
                get() = "Show hints for:"

            override val cases: List<ImmediateConfigurable.Case>
                get() = listOf(
                    ImmediateConfigurable.Case("Argument names", "hints.argument.names", settings::parameterNames)
                )
        }
    }

    override fun createSettings(): Settings = Settings(parameterNames = false) // todo: check state

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink): InlayHintsCollector? {
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val resolved = HintType.resolve(element) ?: return true
                val enabled = when (resolved) {
                    HintType.PARAMETER_HINT -> settings.parameterNames
                    else -> false
                }

                if (!enabled) return true

                resolved.provideHints(element)
                    .mapNotNull { info -> convert(info, editor.project) }
                    .forEach { triple -> sink.addInlineElement(triple.first, triple.second, triple.third) }
                return true
            }

            // todo: take care of black list

            fun convert(inlayInfo: InlayInfo, project: Project?): Triple<Int, Boolean, InlayPresentation>? {
                if (project == null) return null
                val inlayText = getInlayPresentation(inlayInfo.text)
                val presentation = factory.roundWithBackground(factory.smallText(inlayText))

                val finalPresentation = InsetPresentation(MenuOnClickPresentation(presentation, project) {
                    val provider = this@KotlinParameterNameHintsProvider
                    listOf(
                        InlayProviderDisablingAction(provider.name, file.language, project, provider.key),
                        ShowInlayHintsSettings()
                    )
                }, left = 1)

                return Triple(inlayInfo.offset, inlayInfo.relatesToPrecedingText, finalPresentation)
            }

            fun getInlayPresentation(inlayText: String): String =
                if (inlayText.startsWith(TYPE_INFO_PREFIX)) {
                    inlayText.substring(TYPE_INFO_PREFIX.length)
                } else {
                    "$inlayText:"
                }
        }
    }
}


@Suppress("UnstableApiUsage")
class KotlinReferencesTypeHintsProvider : InlayHintsProvider<KotlinReferencesTypeHintsProvider.Settings> {

    data class Settings(
        var propertyType: Boolean = false,
        var localVariableType: Boolean = false,
        var functionReturnType: Boolean = false,
        var parameterType: Boolean = false
    )

    override val key: SettingsKey<Settings>
        get() = SettingsKey("KotlinReferencesTypeHints")
    override val name: String
        get() = "Reference type"
    override val previewText: String?
        get() = ""

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = panel {}

            override val mainCheckboxText: String
                get() = "Show hints for:"

            override val cases: List<ImmediateConfigurable.Case>
                get() = listOf(
                    ImmediateConfigurable.Case("Property type", "hints.type.property", settings::propertyType),
                    ImmediateConfigurable.Case("Local variable type", "hints.type.variable", settings::localVariableType),
                    ImmediateConfigurable.Case("Function return type", "hints.type.function.return", settings::functionReturnType),
                    ImmediateConfigurable.Case("Parameter type", "hints.type.function.parameter", settings::parameterType),
                )
        }
    }

    override fun createSettings(): Settings = Settings(false, false, false, false)

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink): InlayHintsCollector? {
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val resolved = HintType.resolve(element) ?: return true
                val enabled = when (resolved) {
                    HintType.PROPERTY_HINT -> settings.propertyType
                    HintType.LOCAL_VARIABLE_HINT -> settings.localVariableType
                    HintType.FUNCTION_HINT -> settings.functionReturnType
                    HintType.PARAMETER_TYPE_HINT -> settings.parameterType
                    else -> false
                }

                if (!enabled) return true

                resolved.provideHints(element)
                    .mapNotNull { info -> convert(info, editor.project) }
                    .forEach { triple -> sink.addInlineElement(triple.first, triple.second, triple.third) }
                return true
            }

            // todo: take care of black list

            fun convert(inlayInfo: InlayInfo, project: Project?): Triple<Int, Boolean, InlayPresentation>? {
                if (project == null) return null
                val inlayText = getInlayPresentation(inlayInfo.text)
                val presentation = factory.roundWithBackground(factory.smallText(inlayText))

                val finalPresentation = InsetPresentation(MenuOnClickPresentation(presentation, project) {
                    val provider = this@KotlinReferencesTypeHintsProvider
                    listOf(
                        InlayProviderDisablingAction(provider.name, file.language, project, provider.key),
                        ShowInlayHintsSettings()
                    )
                }, left = 1)

                return Triple(inlayInfo.offset, inlayInfo.relatesToPrecedingText, finalPresentation)
            }

            fun getInlayPresentation(inlayText: String): String =
                if (inlayText.startsWith(TYPE_INFO_PREFIX)) {
                    inlayText.substring(TYPE_INFO_PREFIX.length)
                } else {
                    "$inlayText:"
                }
        }
    }
}

// todo: localization
// todo: menu doesn't yet work
@Suppress("UnstableApiUsage")
class KotlinLambdasHintsProvider : InlayHintsProvider<KotlinLambdasHintsProvider.Settings> {

    data class Settings(
        var returnExpressions: Boolean = false,
        var implicitReceiversAndParams: Boolean = false,
    )

    override val key: SettingsKey<Settings> = SettingsKey("KotlinLambdaHints")
    override val name: String = "Lambdas"
    override val previewText: String? = ""

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = panel {}

            override val mainCheckboxText: String
                get() = "Show hints for:"

            override val cases: List<ImmediateConfigurable.Case>
                get() = listOf(
                    ImmediateConfigurable.Case("Lambda return expression", "hints.lambda.return_expr", settings::returnExpressions),
                    ImmediateConfigurable.Case(
                        "Implicit receivers and parameters of lambdas",
                        "hints.lambda.receivers.parameters",
                        settings::implicitReceiversAndParams
                    )
                )
        }
    }

    override fun createSettings(): Settings = Settings(returnExpressions = false, implicitReceiversAndParams = false) // todo: check state

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink): InlayHintsCollector? {
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val resolved = HintType.resolve(element) ?: return true
                val enabled = when (resolved) {
                    HintType.LAMBDA_RETURN_EXPRESSION -> settings.returnExpressions
                    HintType.LAMBDA_IMPLICIT_PARAMETER_RECEIVER -> settings.implicitReceiversAndParams
                    else -> false
                }

                if (!enabled) return true

                resolved.provideHints(element)
                    .mapNotNull { info -> convert(info, editor.project) }
                    .forEach { triple -> sink.addInlineElement(triple.first, triple.second, triple.third) }
                return true
            }

            // todo: take care of black list

            fun convert(inlayInfo: InlayInfo, project: Project?): Triple<Int, Boolean, InlayPresentation>? {
                if (project == null) return null
                val inlayText = getInlayPresentation(inlayInfo.text)
                val presentation = factory.roundWithBackground(factory.smallText(inlayText))

                val finalPresentation = InsetPresentation(MenuOnClickPresentation(presentation, project) {
                    val provider = this@KotlinLambdasHintsProvider
                    listOf(
                        InlayProviderDisablingAction(provider.name, file.language, project, provider.key),
                        ShowInlayHintsSettings()
                    )
                }, left = 1)

                return Triple(inlayInfo.offset, inlayInfo.relatesToPrecedingText, finalPresentation)
            }

            fun getInlayPresentation(inlayText: String): String =
                if (inlayText.startsWith(TYPE_INFO_PREFIX)) {
                    inlayText.substring(TYPE_INFO_PREFIX.length)
                } else {
                    "$inlayText:"
                }
        }
    }
}


@Suppress("UnstableApiUsage")
class KotlinSuspendingCallHintsProvider : InlayHintsProvider<KotlinSuspendingCallHintsProvider.Settings> {

    data class Settings(
        var suspendingCalls: Boolean = false
    )

    override val key: SettingsKey<Settings> = SettingsKey("KotlinSuspendingCallHints")
    override val name: String = "Suspending calls"
    override val previewText: String? = ""

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = panel {}

            override val mainCheckboxText: String
                get() = "Show hints for:"

            override val cases: List<ImmediateConfigurable.Case>
                get() = listOf(
                    ImmediateConfigurable.Case("Suspending calls", "hints.suspending.calls", settings::suspendingCalls)
                )
        }
    }

    override fun createSettings(): Settings = Settings(suspendingCalls = false) // todo: check state

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink): InlayHintsCollector? {
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val resolved = HintType.resolve(element) ?: return true
                val enabled = when (resolved) {
                    HintType.SUSPENDING_CALL -> settings.suspendingCalls
                    else -> false
                }

                if (!enabled) return true

                resolved.provideHints(element)
                    .mapNotNull { info -> convert(info, editor.project) }
                    .forEach { triple -> sink.addInlineElement(triple.first, triple.second, triple.third) }
                return true
            }

            // todo: take care of black list

            fun convert(inlayInfo: InlayInfo, project: Project?): Triple<Int, Boolean, InlayPresentation>? {
                if (project == null) return null
                val inlayText = getInlayPresentation(inlayInfo.text)
                val presentation = factory.roundWithBackground(factory.smallText(inlayText))

                val finalPresentation = InsetPresentation(MenuOnClickPresentation(presentation, project) {
                    val provider = this@KotlinSuspendingCallHintsProvider
                    listOf(
                        InlayProviderDisablingAction(provider.name, file.language, project, provider.key),
                        ShowInlayHintsSettings()
                    )
                }, left = 1)

                return Triple(inlayInfo.offset, inlayInfo.relatesToPrecedingText, finalPresentation)
            }

            fun getInlayPresentation(inlayText: String): String =
                if (inlayText.startsWith(TYPE_INFO_PREFIX)) {
                    inlayText.substring(TYPE_INFO_PREFIX.length)
                } else {
                    "$inlayText:"
                }
        }
    }
}

class ShowInlayHintsSettings : AnAction("Hints Settings...") {
    override fun actionPerformed(e: AnActionEvent) {
        val file = CommonDataKeys.PSI_FILE.getData(e.dataContext) ?: return
        val fileLanguage = file.language
        InlayHintsConfigurable.showSettingsDialogForLanguage(file.project, fileLanguage) // todo: more precise navigation needed
    }
}