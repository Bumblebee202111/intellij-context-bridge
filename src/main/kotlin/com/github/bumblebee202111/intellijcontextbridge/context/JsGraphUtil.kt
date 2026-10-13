package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.JSFunction
import com.intellij.lang.javascript.psi.JSVarStatement
import com.intellij.lang.javascript.psi.ecmal4.JSClass
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
}