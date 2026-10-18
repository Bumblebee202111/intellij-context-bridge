package com.github.bumblebee202111.intellijcontextbridge.context.extractors.js

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageGraphExtractor
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class VueGraphExtractor : LanguageGraphExtractor {

    override fun isSupported(file: PsiFile): Boolean = file.name.endsWith(".vue")

    override fun getImportedNames(file: PsiFile): Set<String> {
        val importedNames = mutableSetOf<String>()

        fun findImports(element: PsiElement) {
            if (element is ES6ImportDeclaration) {
                JsGraphUtil.extractImports(element, importedNames)
                return
            }
            for (child in element.children) {
                findImports(child)
            }
        }

        findImports(file)
        return importedNames
    }

    override fun getPrimaryDeclarations(file: PsiFile): List<PsiElement> {
        val declarations = mutableListOf<PsiElement>(file)

        fun findAndCollect(element: PsiElement) {
            if (element is JSEmbeddedContent) {
                for (child in element.children) {
                    JsGraphUtil.collectDeclarations(child, declarations)
                }
                return
            }
            for (child in element.children) {
                findAndCollect(child)
            }
        }

        findAndCollect(file)
        return declarations
    }
}