package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.openapi.extensions.ExtensionPointName
import com.intellij.psi.PsiFile

interface LanguageSkeletonExtractor {
    companion object {
        val EP_NAME = ExtensionPointName.create<LanguageSkeletonExtractor>("com.github.bumblebee202111.intellijcontextbridge.skeletonExtractor")
    }

    fun isSupported(file: PsiFile): Boolean
    fun extract(file: PsiFile): String?
}