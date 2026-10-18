package com.github.bumblebee202111.intellijcontextbridge.context.core

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageSkeletonExtractor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

object PsiSkeletonExtractor {

    fun extract(project: Project, file: VirtualFile): String? {
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(file) ?: return null

        val extractor = LanguageSkeletonExtractor.Companion.EP_NAME.extensionList.firstOrNull { it.isSupported(psiFile) }
        return extractor?.extract(psiFile)
    }
}