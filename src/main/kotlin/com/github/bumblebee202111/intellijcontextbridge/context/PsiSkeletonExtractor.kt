package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

object PsiSkeletonExtractor {

    private val extractors: List<LanguageSkeletonExtractor> = listOf(
        KotlinSkeletonExtractor(),
        JavaSkeletonExtractor()
    )

    fun extract(project: Project, file: VirtualFile): String? {
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(file) ?: return null

        val extractor = extractors.firstOrNull { it.isSupported(psiFile) }
        return extractor?.extract(psiFile)
    }
}