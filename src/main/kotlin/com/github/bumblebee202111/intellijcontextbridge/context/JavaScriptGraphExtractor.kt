package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class JavaScriptGraphExtractor : LanguageGraphExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is JSFile

    override fun getImportedNames(file: PsiFile): Set<String> {
        val jsFile = file as? JSFile ?: return emptySet()
        val importedNames = mutableSetOf<String>()

        jsFile.children.forEach { element ->
            if (element is ES6ImportDeclaration) {
                element.importedBindings.forEach { binding ->
                    binding.name?.let { importedNames.add(it) }
                }
            }
        }

        return importedNames
    }

    override fun getPrimaryDeclarations(file: PsiFile): List<PsiElement> {
        val jsFile = file as? JSFile ?: return emptyList()
        return jsFile.children.filterIsInstance<JSClass>()
    }
}