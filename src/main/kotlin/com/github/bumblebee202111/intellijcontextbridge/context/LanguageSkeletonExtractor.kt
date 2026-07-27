package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.psi.PsiFile

interface LanguageSkeletonExtractor {
    fun isSupported(file: PsiFile): Boolean
    fun extract(file: PsiFile): String?
}