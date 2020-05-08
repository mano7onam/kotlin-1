/*
 * Copyright 2010-2020 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.codeInsight.hints

import com.intellij.codeInsight.hints.*
import com.intellij.codeInsight.hints.presentation.InlayPresentation
import com.intellij.codeInsight.hints.presentation.InsetPresentation
import com.intellij.codeInsight.hints.presentation.MenuOnClickPresentation
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.ui.layout.panel
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.parameterInfo.HintType
import org.jetbrains.kotlin.idea.parameterInfo.TYPE_INFO_PREFIX
import javax.swing.JComponent

// todo: Most probably we should keep parameter-name-hints under the control of KotlinInlayParameterHintsProvider
// at least methods black list functionality is a platform part


@Suppress("UnstableApiUsage")
class KotlinParameterNameHintsProvider : InlayHintsProvider<KotlinParameterNameHintsProvider.Settings> {

    //todo: join some other menu item/component?
    data class Settings(
        var parameterNames: Boolean = false
    )

    override val key: SettingsKey<Settings> = SettingsKey("KotlinParameterNamesHints")
    override val name: String = KotlinBundle.message("hints.settings.parameters")
    override val previewText: String? = "" // todo: doesn't work out of the box

    override fun createConfigurable(settings: Settings): ImmediateConfigurable {
        return object : ImmediateConfigurable {
            override fun createComponent(listener: ChangeListener): JComponent = panel {}

            override val mainCheckboxText: String = KotlinBundle.message("hints.settings.common.items")

            override val cases: List<ImmediateConfigurable.Case>
                get() = listOf(
                    ImmediateConfigurable.Case(
                        KotlinBundle.message("hints.settings.parameters"),
                        "hints.parameter.names",
                        settings::parameterNames
                    )
                )
        }
    }

    override fun createSettings(): Settings = Settings(parameterNames = false) // todo: check state

    override fun getCollectorFor(file: PsiFile, editor: Editor, settings: Settings, sink: InlayHintsSink): InlayHintsCollector? {
        return object : FactoryInlayHintsCollector(editor) {
            override fun collect(element: PsiElement, editor: Editor, sink: InlayHintsSink): Boolean {
                val resolved = HintType.resolve(element) ?: return true
                val enabled = when (resolved) {
                    HintType.PARAMETER_HINT -> settings.parameterNames //todo: extract HintType
                    else -> false
                }

                if (!enabled) return true

                resolved.provideHints(element)
                    .mapNotNull { info -> convert(info, editor.project) }
                    .forEach { triple -> sink.addInlineElement(triple.first, triple.second, triple.third) }
                return true
            }

            // todo: take care of black list
            /*
             Language language = file.getLanguage(); // PsiFile
             MethodInfoBlacklistFilter.forLanguage(language) // type is HintInfoFilter

             Language dependentLanguage = provider.getBlackListDependencyLanguage(); // Java
             blackList.addAll(blacklist(dependentLanguage));
                ...
                myHintInfoFilter.showHint(info);

             */
            fun convert(inlayInfo: InlayInfo, project: Project?): Triple<Int, Boolean, InlayPresentation>? {
                val inlayText = getInlayPresentation(inlayInfo.text)
                val presentation = factory.roundWithBackground(factory.smallText(inlayText))

                val finalPresentation = if (project == null) presentation else
                    InsetPresentation(
                        MenuOnClickPresentation(presentation, project) {
                            val provider = this@KotlinParameterNameHintsProvider
                            listOf(
                                InlayProviderDisablingAction(provider.name, file.language, project, provider.key),
                                ShowInlayHintsSettings()
                            )
                        }, left = 1
                    )

                return Triple(inlayInfo.offset, inlayInfo.relatesToPrecedingText, finalPresentation)
            }

            fun getInlayPresentation(inlayText: String): String = //todo looks like all providers need it
                if (inlayText.startsWith(TYPE_INFO_PREFIX)) {
                    inlayText.substring(TYPE_INFO_PREFIX.length)
                } else {
                    "$inlayText:"
                }
        }
    }
}