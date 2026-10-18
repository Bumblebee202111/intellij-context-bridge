package com.github.bumblebee202111.intellijcontextbridge.context.extractors.js

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageGraphExtractor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class JavaScriptGraphExtractor : LanguageGraphExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is JSFile

    override fun getImportedNames(file: PsiFile): Set<String> {
        val jsFile = file as? JSFile ?: return emptySet()
        val importedNames = mutableSetOf<String>()

        jsFile.children.forEach { element ->
            if (element is ES6ImportDeclaration) {
                JsGraphUtil.extractImports(element, importedNames)
            }
        }

        return importedNames
    }

    override fun getPrimaryDeclarations(file: PsiFile): List<PsiElement> {
        val jsFile = file as? JSFile ?: return emptyList()
        val declarations = mutableListOf<PsiElement>()

        for (child in jsFile.children) {
            JsGraphUtil.collectDeclarations(child, declarations)
        }

        return declarations
    }
}