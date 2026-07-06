package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

object PsiSkeletonExtractor {

    fun extract(project: Project, file: VirtualFile): String? {
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(file) ?: return null

        return when (psiFile) {
            is KtFile -> extractKotlin(psiFile)
            is PsiJavaFile -> extractJava(psiFile)
            else -> null // Fallback for non-code files
        }
    }

    private fun extractKotlin(file: KtFile): String {
        val builder = StringBuilder()

        // 1. Package
        file.packageDirective?.let {
            if (it.text.isNotBlank()) builder.append(it.text).append("\n\n")
        }

        // 2. Process Declarations (Classes, Functions, Properties)
        val bodyText = file.declarations.mapNotNull { processKtDeclaration(it) }.joinToString("\n\n")

        // 3. Filter Imports (Keep only if the imported short name is used in the public API body)
        val usedImports = file.importDirectives.filter { import ->
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

    private fun extractJava(file: PsiJavaFile): String {
        val builder = StringBuilder()

        file.packageStatement?.let { builder.append(it.text).append("\n\n") }

        val bodyText = file.classes.mapNotNull { processJavaClass(it, "") }.joinToString("\n\n")

        val usedImports = file.importList?.allImportStatements?.filter { import ->
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