package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.psi.JavaTokenType
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiJavaToken
import com.intellij.psi.PsiModifier

class JavaSkeletonExtractor : LanguageSkeletonExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is PsiJavaFile

    override fun extract(file: PsiFile): String? {
        val javaFile = file as? PsiJavaFile ?: return null
        val builder = StringBuilder()

        javaFile.packageStatement?.let { builder.append(it.text).append("\n\n") }

        val bodyText = javaFile.classes.mapNotNull { processJavaClass(it, "") }.joinToString("\n\n")

        val usedImports = javaFile.importList?.allImportStatements?.filter { import ->
            val name = import.importReference?.referenceName ?: return@filter false
            bodyText.contains(name)
        } ?: emptyList()

        usedImports.forEach { builder.append(it.text).append("\n") }
        if (usedImports.isNotEmpty()) builder.append("\n")

        builder.append(bodyText)
        return builder.toString().trim()
    }

    private fun processJavaClass(psiClass: PsiClass, indent: String): String? {
        // Strip private (Package-private is kept for intra-module LLM context)
        if (psiClass.hasModifierProperty(PsiModifier.PRIVATE)) return null

        val doc = psiClass.docComment?.text?.let { "$indent$it\n" } ?: ""

        val modifiers = psiClass.modifierList?.text ?: ""
        val keyword = if (psiClass.isInterface) "interface" else if (psiClass.isEnum) "enum" else "class"
        val name = psiClass.name
        val typeParams = psiClass.typeParameterList?.text ?: ""
        val extends = psiClass.extendsList?.text?.let { if (it.isNotBlank()) " $it" else "" } ?: ""
        val implements = psiClass.implementsList?.text?.let { if (it.isNotBlank()) " $it" else "" } ?: ""

        val header = "$modifiers $keyword $name$typeParams$extends$implements".trim()
        val children = mutableListOf<String>()

        psiClass.fields.forEach { field ->
            if (!field.hasModifierProperty(PsiModifier.PRIVATE)) {
                val fDoc = field.docComment?.text?.let { "$indent    $it\n" } ?: ""

                val initializer = field.initializer
                val isStaticFinal = field.hasModifierProperty(PsiModifier.STATIC) && field.hasModifierProperty(PsiModifier.FINAL)

                var endOffset = field.textRange.endOffset

                // Strip initializer ONLY if it is not a constant
                if (initializer != null && !isStaticFinal) {
                    val equalsToken = field.children.find { it is PsiJavaToken && it.tokenType == JavaTokenType.EQ }
                    if (equalsToken != null) {
                        endOffset = equalsToken.textRange.startOffset
                    }
                }

                var fText = field.containingFile.text.substring(field.textRange.startOffset, endOffset).trimEnd()
                if (!fText.endsWith(";")) fText += ";"
                children.add("$fDoc$indent    $fText")
            }
        }

        psiClass.methods.forEach { method ->
            if (!method.hasModifierProperty(PsiModifier.PRIVATE)) {
                val mDoc = method.docComment?.text?.let { "$indent    $it\n" } ?: ""

                val body = method.body
                val endOffset = body?.textRange?.startOffset ?: method.textRange.endOffset

                var mText = method.containingFile.text.substring(method.textRange.startOffset, endOffset).trimEnd()
                if (!mText.endsWith(";")) mText += ";"
                children.add("$mDoc$indent    $mText")
            }
        }

        psiClass.innerClasses.forEach { inner ->
            processJavaClass(inner, "$indent    ")?.let { children.add(it) }
        }

        return if (children.isEmpty()) {
            "$doc$indent$header {}"
        } else {
            "$doc$indent$header {\n${children.joinToString("\n\n")}\n$indent}"
        }
    }
}