package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias

class KotlinGraphExtractor : LanguageGraphExtractor {

    override fun isSupported(file: PsiFile): Boolean = file is KtFile

    override fun getImportedNames(file: PsiFile): Set<String> {
        val ktFile = file as? KtFile ?: return emptySet()
        val importedNames = mutableSetOf<String>()

        ktFile.importDirectives.forEach { import ->
            import.importedName?.asString()?.let { importedNames.add(it) }
        }

        return importedNames
    }

    override fun getPrimaryDeclarations(file: PsiFile): List<PsiElement> {
        val ktFile = file as? KtFile ?: return emptyList()
        return ktFile.declarations.filter { decl ->
            !decl.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
            (decl is KtClassOrObject || decl is KtNamedFunction || decl is KtProperty || decl is KtTypeAlias)
        }
    }
}