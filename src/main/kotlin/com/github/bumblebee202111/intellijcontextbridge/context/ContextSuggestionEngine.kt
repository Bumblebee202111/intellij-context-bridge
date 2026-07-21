package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import kotlin.math.sqrt

object ContextSuggestionEngine {

    fun calculateSuggestions(project: Project, contextState: ContextState, promptText: String): Set<VirtualFile> {
        val scores = mutableMapOf<VirtualFile, Int>()
        val seedFiles = mutableSetOf<VirtualFile>()

        fun award(file: VirtualFile, points: Int) {
            if (!file.isValid || file.isDirectory || FileFilterUtil.isIgnored(project, file)) return
            scores[file] = (scores[file] ?: 0) + points
        }

        // 1. Active File (+50)
        val fileEditorManager = FileEditorManager.getInstance(project)
        val activeFiles = fileEditorManager.selectedFiles.toSet()
        activeFiles.forEach {
            award(it, 50)
            seedFiles.add(it)
        }

        // 2. Open Files (+20)
        fileEditorManager.openFiles.forEach {
            if (!activeFiles.contains(it)) {
                award(it, 20)
                seedFiles.add(it)
            }
        }

        // 3. Git Changes (+40)
        ChangeListManager.getInstance(project).affectedFiles.forEach {
            award(it, 40)
            seedFiles.add(it)
        }

        // 4. Prompt Mentions (+30)
        val projectScope = GlobalSearchScope.projectScope(project)
        val allFilenames = FilenameIndex.getAllFilenames(project)
        val words = promptText.split(Regex("\\W+")).filter { it.length > 2 }.toSet()

        if (words.isNotEmpty()) {
            val matchedNames = allFilenames.filter { name ->
                ProgressManager.checkCanceled() // Ensure fast cancellation during index filtering
                val nameWithoutExt = name.substringBeforeLast('.')
                words.any { word -> nameWithoutExt.equals(word, ignoreCase = true) }
            }
            matchedNames.forEach { name ->
                FilenameIndex.getVirtualFilesByName(name, projectScope).forEach { file ->
                    award(file, 30)
                    seedFiles.add(file)
                }
            }
        }

        // 5. Expand Seeds with FULL Context Files (Excluding SKELETON)
        contextState.fileStates.forEach { (file, level) ->
            if (level == ContextLevel.FULL) {
                seedFiles.add(file)
            }
        }

        // 6. Unbounded Graph Traversal (Dependencies & Usages)
        val psiManager = PsiManager.getInstance(project)

        for (seed in seedFiles) {
            ProgressManager.checkCanceled()
            val psiFile = psiManager.findFile(seed) ?: continue

            // --- DEPENDENCIES (Outgoing) ---
            val importedNames = mutableSetOf<String>()
            if (psiFile is KtFile) {
                psiFile.importDirectives.forEach { import ->
                    import.importedName?.asString()?.let { importedNames.add(it) }
                }
            } else if (psiFile is PsiJavaFile) {
                psiFile.importList?.allImportStatements?.forEach { import ->
                    import.importReference?.referenceName?.let { importedNames.add(it) }
                }
            }

            if (importedNames.isNotEmpty()) {
                val matchedNames = allFilenames.filter { name ->
                    ProgressManager.checkCanceled()
                    val nameWithoutExt = name.substringBeforeLast('.')
                    importedNames.contains(nameWithoutExt)
                }
                matchedNames.forEach { name ->
                    FilenameIndex.getVirtualFilesByName(name, projectScope).forEach { importedFile ->
                        award(importedFile, 15) // +15 for being a 1st-degree dependency
                    }
                }
            }

            // --- USAGES (Incoming) ---
            val primaryElements = mutableListOf<PsiElement>()
            if (psiFile is KtFile) {
                // Collect ALL non-private top-level declarations (Uncapped)
                val topLevelDeclarations = psiFile.declarations.filter { decl ->
                    !decl.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
                    (decl is KtClassOrObject || decl is KtNamedFunction || decl is KtProperty || decl is KtTypeAlias)
                }
                primaryElements.addAll(topLevelDeclarations)
            } else if (psiFile is PsiJavaFile) {
                primaryElements.addAll(psiFile.classes)
            }

            for (element in primaryElements) {
                ProgressManager.checkCanceled()

                // CPU THROTTLE: Intentionally slow down the unbounded search to prevent CPU spiking
                // and massive GC pressure. This makes the engine "slow and lazy" but power-friendly.
                Thread.sleep(5)

                // UNBOUNDED SEARCH: Find all usages across the project
                val references = ReferencesSearch.search(element, projectScope).findAll()
                if (references.isEmpty()) continue

                // INVERSE DOCUMENT FREQUENCY (IDF) SCORING: 40 / sqrt(N)
                // 1 usage = 40 pts, 4 usages = 20 pts, 16 usages = 10 pts, 100 usages = 4 pts
                val idfScore = (40.0 / sqrt(references.size.toDouble())).toInt()

                if (idfScore > 0) {
                    references.forEach { ref ->
                        ProgressManager.checkCanceled()
                        val callerFile = ref.element.containingFile?.virtualFile
                        if (callerFile != null && callerFile != seed) {
                            award(callerFile, idfScore)
                        }
                    }
                }
            }
        }

        // 7. Filter & Sort
        return scores.entries
            .filter { (file, score) ->
                contextState.getLevel(file) == ContextLevel.NONE && // EXCLUSION: Disappear if already in context
                score >= 15 // Minimum threshold to be suggested
            }
            .sortedByDescending { it.value }
            .take(15) // Cap tree size to prevent UI vertical clutter
            .map { it.key }
            .toSet()
    }
}