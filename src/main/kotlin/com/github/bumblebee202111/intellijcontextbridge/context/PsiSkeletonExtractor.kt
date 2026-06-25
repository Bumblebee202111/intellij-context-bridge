package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*

object PsiSkeletonExtractor {

    fun extract(project: Project, file: VirtualFile): String {
        val psiManager = PsiManager.getInstance(project)
        val psiFile = psiManager.findFile(file) ?: return file.path

        return when (psiFile) {
            is KtFile -> extractKotlin(psiFile)
            is PsiJavaFile -> extractJava(psiFile)
            else -> file.path // Fallback for non-code files (Name-Only)
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
        // Strip private and internal members
        if (declaration.hasModifier(KtTokens.PRIVATE_KEYWORD) || declaration.hasModifier(KtTokens.INTERNAL_KEYWORD)) {
            return null
        }

        val doc = declaration.docComment?.text?.let { "$indent$it\n" } ?: ""

        return when (declaration) {
            is KtClassOrObject -> {
                val headerText = declaration.text.substringBefore("{").trim()
                val children = declaration.body?.declarations?.mapNotNull { processKtDeclaration(it, "$indent    ") } ?: emptyList()

                if (children.isEmpty()) {
                    "$doc$indent$headerText"
                } else {
                    "$doc$indent$headerText {\n${children.joinToString("\n\n")}\n$indent}"
                }
            }
            is KtNamedFunction -> {
                var sig = declaration.text
                declaration.bodyExpression?.let { sig = sig.replace(it.text, "") }
                sig = sig.trimEnd('=', ' ', '\n', '{')
                "$doc$indent$sig"
            }
            is KtProperty -> {
                var sig = declaration.text
                declaration.initializer?.let { sig = sig.replace(it.text, "") }
                declaration.delegateExpression?.let { sig = sig.replace(it.text, "") }
                declaration.accessors.forEach { acc -> sig = sig.replace(acc.text, "") }
                sig = sig.trimEnd('=', ' ', '\n', 'b', 'y')
                "$doc$indent$sig"
            }
            else -> "$doc$indent${declaration.text}"
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
        // Strip private and package-private
        if (psiClass.hasModifierProperty(PsiModifier.PRIVATE) || psiClass.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) return null

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
            if (!field.hasModifierProperty(PsiModifier.PRIVATE) && !field.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) {
                val fDoc = field.docComment?.text?.let { "$indent    $it\n" } ?: ""
                var fText = field.text
                field.initializer?.let { fText = fText.replace(it.text, "") }
                fText = fText.trimEnd('=', ' ')
                if (!fText.endsWith(";")) fText += ";"
                children.add("$fDoc$indent    $fText")
            }
        }

        psiClass.methods.forEach { method ->
            if (!method.hasModifierProperty(PsiModifier.PRIVATE) && !method.hasModifierProperty(PsiModifier.PACKAGE_LOCAL)) {
                val mDoc = method.docComment?.text?.let { "$indent    $it\n" } ?: ""
                var mText = method.text
                method.body?.let { mText = mText.replace(it.text, "") }
                mText = mText.trimEnd(' ', '\n', '{')
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