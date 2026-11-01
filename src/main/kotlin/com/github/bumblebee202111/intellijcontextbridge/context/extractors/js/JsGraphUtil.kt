package com.github.bumblebee202111.intellijcontextbridge.context.extractors.js

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSBlockStatement
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.openapi.progress.ProgressManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiNamedElement

object JsGraphUtil {
    private val IMPORT_FROM_REGEX = Regex("""(?:from\s+|require\(\s*|import\(\s*|import\s+)['"]([^'"]+)['"]""")
    private val SPECIFIERS_REGEX = Regex("""\{([^}]+)\}""")

    fun extractImports(element: ES6ImportDeclaration, target: MutableSet<String>) {
        val text = element.text

        IMPORT_FROM_REGEX.find(text)?.let { match ->
            val path = match.groupValues[1].trim()
            val rawName = path.substringAfterLast('/')
            val nameWithoutExt = rawName.substringBeforeLast('.')
            if (nameWithoutExt.isNotBlank()) {
                target.add(nameWithoutExt)
            }
        }

        SPECIFIERS_REGEX.find(text)?.let { match ->
            match.groupValues[1].split(",").forEach { spec ->
                val specName = spec.trim().split(Regex("""\s+as\s+""")).last().trim()
                if (specName.isNotBlank()) {
                    target.add(specName)
                }
            }
        }

        element.importedBindings.forEach { binding ->
            binding.name?.let { target.add(it) }
        }
    }

    fun collectDeclarations(element: PsiElement, target: MutableList<PsiElement>) {
        if (element is JSClass) {
            if (!element.name.isNullOrBlank()) target.add(element)
            return
        }
        if (element is JSFunction) {
            if (!element.name.isNullOrBlank()) target.add(element)
            return
        }
        if (element is JSVarStatement) {
            for (decl in element.declarations) {
                if (!decl.name.isNullOrBlank()) target.add(decl)
            }
            return
        }
        if (element is ES6ExportDeclaration) {
            for (child in element.children) {
                collectDeclarations(child, target)
            }
            return
        }

        val className = element.javaClass.simpleName
        if (className.contains("TypeAlias") ||
            className.contains("Interface") ||
            className.contains("Enum")) {
            if (element is PsiNamedElement && !element.name.isNullOrBlank()) {
                target.add(element)
            }
        }
    }

    fun isSharedDeclaration(element: PsiElement): Boolean {
        if (element is ES6ImportDeclaration) return true
        if (element is ES6ExportDeclaration) return true
        if (element is ES6ExportDefaultAssignment) return true
        if (element is JSClass) return true

        val name = element.javaClass.simpleName
        return name.contains("TypeAlias") ||
            name.contains("TypeScriptEnum") ||
            name.contains("TypeScriptModule")
    }

    fun stripFunctionBlocks(element: PsiElement): String {
        val fileText = element.containingFile.text
        val replacements = mutableListOf<Pair<IntRange, String>>()

        fun visit(el: PsiElement) {
            ProgressManager.checkCanceled()
            if (el is JSBlockStatement && el.parent is JSFunction) {
                replacements.add(el.textRange.startOffset..el.textRange.endOffset to "{ /* ... */ }")
                return
            }

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