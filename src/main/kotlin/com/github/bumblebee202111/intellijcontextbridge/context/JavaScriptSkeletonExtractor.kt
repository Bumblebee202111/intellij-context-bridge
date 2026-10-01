package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile

class JavaScriptSkeletonExtractor : LanguageSkeletonExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is JSFile

    override fun extract(file: PsiFile): String? {
        val jsFile = file as? JSFile ?: return null
        val builder = StringBuilder()

        for (element in jsFile.children) {
            if (isDeclaration(element)) {
                val stripped = stripFunctionBlocks(element)
                if (stripped.isNotBlank()) {
                    builder.append(stripped).append("\n\n")
                }
            }
        }

        return builder.toString().trim()
    }

    private fun isDeclaration(element: PsiElement): Boolean {
        if (element is ES6ImportDeclaration) return true
        if (element is ES6ExportDeclaration) return true
        if (element is ES6ExportDefaultAssignment) return true
        if (element is JSClass) return true
        if (element is JSFunction) return true
        if (element is JSVarStatement) return true

        val name = element.javaClass.simpleName
        if (name.contains("TypeScriptTypeAlias") ||
            name.contains("TypeScriptEnum") ||
            name.contains("TypeScriptModule") ||
            name.contains("TypeAlias")) {
            return true
        }

        return false
    }

    private fun stripFunctionBlocks(element: PsiElement): String {
        val fileText = element.containingFile.text
        val replacements = mutableListOf<Pair<IntRange, String>>()

        fun visit(el: PsiElement) {
            // 1. Standard Function Blocks
            if (el is JSBlockStatement && el.parent is JSFunction) {
                replacements.add(el.textRange.startOffset..el.textRange.endOffset to "{ /* ... */ }")
                return // Skip children of the block
            }

            // 2. Arrow Functions with Implicit Returns
            if (el is JSFunction && el.javaClass.simpleName.contains("ArrowFunction")) {
                val lastChild = el.lastChild
                if (lastChild != null && lastChild !is JSBlockStatement) {
                    replacements.add(lastChild.textRange.startOffset..lastChild.textRange.endOffset to "/* ... */")
                    return
                }
            }

            el.children.forEach { visit(it) }
        }

        visit(element)

        if (replacements.isEmpty()) return element.text

        replacements.sortBy { it.first.first }

        val sb = StringBuilder()
        var currentOffset = element.textRange.startOffset

        for ((range, replacement) in replacements) {
            // Avoid overlapping replacements
            if (range.first >= currentOffset) {
                sb.append(fileText.substring(currentOffset, range.first))
                sb.append(replacement)
                currentOffset = range.last
            }
        }
        sb.append(fileText.substring(currentOffset, element.textRange.endOffset))

        return sb.toString()
    }
}