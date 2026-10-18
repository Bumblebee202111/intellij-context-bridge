package com.github.bumblebee202111.intellijcontextbridge.context.ep

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

interface LanguageGraphExtractor {
    companion object {
        val EP_NAME = ExtensionPointName.Companion.create<LanguageGraphExtractor>("com.github.bumblebee202111.intellijcontextbridge.graphExtractor")
    }

    fun isSupported(file: PsiFile): Boolean
    fun getImportedNames(file: PsiFile): Set<String>
    fun getPrimaryDeclarations(file: PsiFile): List<PsiElement>
}