package com.github.bumblebee202111.intellijcontextbridge.context.extractors.js

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageSkeletonExtractor
import com.intellij.lang.javascript.psi.JSFile
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class JavaScriptSkeletonExtractor : LanguageSkeletonExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is JSFile

    override fun extract(file: PsiFile): String? {
        val jsFile = file as? JSFile ?: return null
        val builder = StringBuilder()

        for (element in jsFile.children) {
            if (isDeclaration(element)) {
                val stripped = JsGraphUtil.stripFunctionBlocks(element)
                if (stripped.isNotBlank()) {
                    builder.append(stripped).append("\n\n")
                }
            }
        }

        return builder.toString().trim()
    }

    private fun isDeclaration(element: PsiElement): Boolean {
        return JsGraphUtil.isSharedDeclaration(element) ||
            element is JSFunction ||
            element is JSVarStatement
    }
}