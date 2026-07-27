package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtAnonymousInitializer
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtDeclaration
import org.jetbrains.kotlin.psi.KtEnumEntry
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtSecondaryConstructor

class KotlinSkeletonExtractor : LanguageSkeletonExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is KtFile

    override fun extract(file: PsiFile): String? {
        val ktFile = file as? KtFile ?: return null
        val builder = StringBuilder()

        // 1. Package
        ktFile.packageDirective?.let {
            if (it.text.isNotBlank()) builder.append(it.text).append("\n\n")
        }

        // 2. Process Declarations (Classes, Functions, Properties)
        val bodyText = ktFile.declarations.mapNotNull { processKtDeclaration(it) }.joinToString("\n\n")

        // 3. Filter Imports (Keep only if the imported short name is used in the public API body)
        val usedImports = ktFile.importDirectives.filter { import ->
            val importedName = import.importedName?.asString() ?: return@filter false
            bodyText.contains(importedName)
        }

        usedImports.forEach { builder.append(it.text).append("\n") }
        if (usedImports.isNotEmpty()) builder.append("\n")

        builder.append(bodyText)

        return builder.toString().trim()
    }

    private fun processKtDeclaration(declaration: KtDeclaration, indent: String = ""): String? {
        // Strip private members (Internal is kept for intra-module LLM context)
        if (declaration.hasModifier(KtTokens.PRIVATE_KEYWORD)) return null

        val doc = declaration.docComment?.text?.let { "$indent$it\n" } ?: ""

        return when (declaration) {
            is KtEnumEntry -> {
                val body = declaration.body
                val headerEnd = body?.textRange?.startOffset ?: declaration.textRange.endOffset
                val headerText = declaration.containingFile.text.substring(declaration.textRange.startOffset, headerEnd).trimEnd()

                val children = declaration.body?.declarations?.mapNotNull { processKtDeclaration(it, "$indent    ") } ?: emptyList()

                if (children.isEmpty()) {
                    "$doc$indent$headerText"
                } else {
                    "$doc$indent$headerText {\n${children.joinToString("\n\n")}\n$indent}"
                }
            }
            is KtClassOrObject -> {
                val body = declaration.body
                val headerEnd = body?.textRange?.startOffset ?: declaration.textRange.endOffset
                val headerText = declaration.containingFile.text.substring(declaration.textRange.startOffset, headerEnd).trim()

                val children = declaration.body?.declarations?.mapNotNull { processKtDeclaration(it, "$indent    ") } ?: emptyList()

                if (children.isEmpty()) {
                    "$doc$indent$headerText {}"
                } else {
                    "$doc$indent$headerText {\n${children.joinToString("\n\n")}\n$indent}"
                }
            }
            is KtNamedFunction -> {
                val body = declaration.bodyExpression
                val equalsToken = declaration.node.findChildByType(KtTokens.EQ)?.psi

                // Safely find the exact AST boundary of the body or equals sign
                val endOffset = equalsToken?.textRange?.startOffset ?: body?.textRange?.startOffset ?: declaration.textRange.endOffset
                val sig = declaration.containingFile.text.substring(declaration.textRange.startOffset, endOffset).trimEnd()

                "$doc$indent$sig"
            }
            is KtSecondaryConstructor -> {
                val body = declaration.bodyExpression
                val endOffset = body?.textRange?.startOffset ?: declaration.textRange.endOffset
                val sig = declaration.containingFile.text.substring(declaration.textRange.startOffset, endOffset).trimEnd()
                "$doc$indent$sig"
            }
            is KtProperty -> {
                val initializer = declaration.initializer
                val delegate = declaration.delegateExpression
                val isConst = declaration.hasModifier(KtTokens.CONST_KEYWORD)
                val hasExplicitType = declaration.typeReference != null

                var endOffset = declaration.textRange.endOffset

                // Strip initializer ONLY if it's not a const AND has an explicit type
                if (!isConst && hasExplicitType) {
                    if (initializer != null) {
                        val equalsToken = declaration.node.findChildByType(KtTokens.EQ)?.psi
                        if (equalsToken != null) endOffset = equalsToken.textRange.startOffset
                    } else if (delegate != null) {
                        val byToken = declaration.node.findChildByType(KtTokens.BY_KEYWORD)?.psi
                        if (byToken != null) endOffset = byToken.textRange.startOffset
                    }
                }

                // Strip custom getter/setter bodies by finding the first accessor
                val firstAccessor = declaration.accessors.firstOrNull()
                if (firstAccessor != null && firstAccessor.textRange.startOffset < endOffset) {
                    endOffset = firstAccessor.textRange.startOffset
                }

                val sig = declaration.containingFile.text.substring(declaration.textRange.startOffset, endOffset).trimEnd()
                "$doc$indent$sig"
            }
            is KtAnonymousInitializer -> null // Purely internal implementation logic, omit entirely
            else -> "$doc$indent${declaration.text}" // Fallback for TypeAliases, etc.
        }
    }
}