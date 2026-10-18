package com.github.bumblebee202111.intellijcontextbridge.context.extractors.java

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageGraphExtractor
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile

class JavaGraphExtractor : LanguageGraphExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is PsiJavaFile

    override fun getImportedNames(file: PsiFile): Set<String> {
        val javaFile = file as? PsiJavaFile ?: return emptySet()
        val importedNames = mutableSetOf<String>()

        javaFile.importList?.allImportStatements?.forEach { import ->
            import.importReference?.referenceName?.let { importedNames.add(it) }
        }

        return importedNames
    }

    override fun getPrimaryDeclarations(file: PsiFile): List<PsiElement> {
        val javaFile = file as? PsiJavaFile ?: return emptyList()
        return javaFile.classes.toList()
    }
}