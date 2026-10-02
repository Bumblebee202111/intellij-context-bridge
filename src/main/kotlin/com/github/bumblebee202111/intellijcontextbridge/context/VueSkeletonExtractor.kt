package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.lang.ecmascript6.psi.ES6ExportDeclaration
import com.intellij.lang.ecmascript6.psi.ES6ExportDefaultAssignment
import com.intellij.lang.ecmascript6.psi.ES6ImportDeclaration
import com.intellij.lang.javascript.psi.*
import com.intellij.lang.javascript.psi.ecmal4.JSClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiWhiteSpace
import com.intellij.psi.xml.XmlFile
import com.intellij.psi.xml.XmlTag

class VueSkeletonExtractor : LanguageSkeletonExtractor {

    private val vueMacros = setOf(
        "defineProps", "defineEmits", "defineExpose", "defineOptions",
        "defineSlots", "defineModel", "withDefaults"
    )

    override fun isSupported(file: PsiFile): Boolean {
        return file is XmlFile && file.name.endsWith(".vue")
    }

    override fun extract(file: PsiFile): String? {
        val xmlFile = file as? XmlFile ?: return null
        val builder = StringBuilder()

        val document = xmlFile.document ?: return null
        for (child in document.children) {
            if (child is XmlTag) {
                processTag(child, builder)
                builder.append("\n\n")
            }
        }

        return builder.toString().trim()
    }

    private fun processTag(tag: XmlTag, builder: StringBuilder) {
        val tagName = tag.name
        val attributes = tag.attributes.joinToString(" ") { it.text }.let { if (it.isEmpty()) "" else " $it" }

        when (tagName) {
            "template" -> {
                builder.append("<template$attributes>\n  <!-- ... -->\n</template>")
            }
            "style" -> {
                builder.append("<style$attributes>\n  /* ... */\n</style>")
            }
            "script" -> {
                builder.append("<script$attributes>\n")
                val isSetup = tag.getAttribute("setup") != null

                val jsContent = findJsContent(tag)
                if (jsContent != null) {
                    if (isSetup) {
                        processScriptSetup(jsContent, builder)
                    } else {
                        processStandardScript(jsContent, builder)
                    }
                } else {
                    builder.append("  /* ... */\n")
                }
                builder.append("</script>")
            }
            else -> {
                builder.append("<$tagName$attributes>\n  <!-- ... -->\n</$tagName>")
            }
        }
    }

    private fun findJsContent(element: PsiElement): PsiElement? {
        if (element is JSEmbeddedContent) return element
        for (child in element.children) {
            val found = findJsContent(child)
            if (found != null) return found
        }
        return null
    }

    private fun processScriptSetup(jsContent: PsiElement, builder: StringBuilder) {
        var hasOmitted = false
        for (element in jsContent.children) {
            if (element is PsiWhiteSpace) continue

            if (isWhitelistSetupStatement(element)) {
                builder.append(element.text.trimEnd()).append("\n")
            } else {
                hasOmitted = true
            }
        }

        if (hasOmitted) {
            builder.append("\n/* ... */\n")
        }
    }

    private fun isWhitelistSetupStatement(element: PsiElement): Boolean {
        if (element is ES6ImportDeclaration) return true
        if (element is ES6ExportDeclaration) return true
        if (element is ES6ExportDefaultAssignment) return true
        if (element is JSClass) return true

        val name = element.javaClass.simpleName
        if (name.contains("TypeScriptTypeAlias") || name.contains("TypeAlias")) return true

        if (element is JSVarStatement) {
            val init = element.declarations.firstOrNull()?.initializer as? JSCallExpression
            val methodName = init?.methodExpression?.text
            if (methodName != null && vueMacros.contains(methodName)) return true
        }

        if (element is JSExpressionStatement) {
            val expr = element.expression as? JSCallExpression
            val methodName = expr?.methodExpression?.text
            if (methodName != null && vueMacros.contains(methodName)) return true
        }

        return false
    }

    private fun processStandardScript(jsContent: PsiElement, builder: StringBuilder) {
        val stripped = stripFunctionBlocks(jsContent)
        builder.append(stripped.trim()).append("\n")
    }

    private fun stripFunctionBlocks(element: PsiElement): String {
        val fileText = element.containingFile.text
        val replacements = mutableListOf<Pair<IntRange, String>>()

        fun visit(el: PsiElement) {
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