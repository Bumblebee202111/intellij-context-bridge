package com.github.bumblebee202111.intellijcontextbridge.context.extractors.js

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageSkeletonExtractor
import com.intellij.lang.javascript.psi.JSCallExpression
import com.intellij.lang.javascript.psi.JSEmbeddedContent
import com.intellij.lang.javascript.psi.JSExpressionStatement
import com.intellij.lang.javascript.psi.JSVarStatement
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
                builder.append(JsGraphUtil.stripFunctionBlocks(element).trimEnd()).append("\n")
            } else {
                hasOmitted = true
            }
        }

        if (hasOmitted) {
            builder.append("\n/* ... */\n")
        }
    }

    private fun isWhitelistSetupStatement(element: PsiElement): Boolean {
        if (JsGraphUtil.isSharedDeclaration(element)) return true

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
        val stripped = JsGraphUtil.stripFunctionBlocks(jsContent)
        builder.append(stripped.trim()).append("\n")
    }
}