package com.github.bumblebee202111.intellijcontextbridge.context

import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.utils.ContextCapabilityUtil
import com.github.bumblebee202111.intellijcontextbridge.utils.FileFilterUtil
import com.intellij.openapi.application.readAction
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiJavaFile
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.delay
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtNamedFunction
import org.jetbrains.kotlin.psi.KtProperty
import org.jetbrains.kotlin.psi.KtTypeAlias
import kotlin.math.sqrt

object ContextSuggestionEngine {

    suspend fun calculateSuggestions(project: Project, contextState: ContextState, promptText: String): Set<VirtualFile> {
        val scores = mutableMapOf<VirtualFile, Int>()
        val seedFiles = mutableSetOf<VirtualFile>()

        fun award(file: VirtualFile, points: Int) {
            if (!file.isValid || file.isDirectory || FileFilterUtil.isIgnored(project, file)) return
            scores[file] = (scores[file] ?: 0) + points
        }

        val psiManager = PsiManager.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)

        // Yielding Read Action: Will suspend if the user types
        val allFilenames = readAction { FilenameIndex.getAllFilenames(project) }

        // Yielding Read Action
        readAction {
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
            val words = promptText.split(Regex("\\W+")).filter { it.length > 2 }.toSet()
            if (words.isNotEmpty()) {
                val matchedNames = allFilenames.filter { name ->
                    ProgressManager.checkCanceled()
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

            // 5. Expand Seeds with COMPLETE Context Files (Excluding SKELETON)
            contextState.fileStates.forEach { (file, level) ->
                if (level == ContextLevel.COMPLETE) {
                    seedFiles.add(file)
                }
            }

            // 6. Dependencies (Outgoing)
            for (seed in seedFiles) {
                ProgressManager.checkCanceled()
                val psiFile = psiManager.findFile(seed) ?: continue

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
                            award(importedFile, 15)
                        }
                    }
                }
            }
        }

        // 7. Unbounded Graph Traversal (Incoming Usages)
        for (seed in seedFiles) {
            // Yielding Read Action
            val primaryElements = readAction {
                val elements = mutableListOf<PsiElement>()
                if (!seed.isValid) return@readAction elements

                val psiFile = psiManager.findFile(seed) ?: return@readAction elements
                if (psiFile is KtFile) {
                    val topLevelDeclarations = psiFile.declarations.filter { decl ->
                        !decl.hasModifier(KtTokens.PRIVATE_KEYWORD) &&
                        (decl is KtClassOrObject || decl is KtNamedFunction || decl is KtProperty || decl is KtTypeAlias)
                    }
                    elements.addAll(topLevelDeclarations)
                } else if (psiFile is PsiJavaFile) {
                    elements.addAll(psiFile.classes)
                }
                elements
            }

            for (element in primaryElements) {
                // CPU THROTTLE: Yield the coroutine thread completely between elements.
                delay(10)

                // Yielding Read Action: Will suspend if the user types during the search
                readAction {
                    if (!element.isValid) return@readAction

                    val usageFiles = mutableSetOf<VirtualFile>()

                    // MEMORY THROTTLE: Use forEach instead of findAll() to prevent massive memory spikes
                    ReferencesSearch.search(element, projectScope).forEach { ref ->
                        ProgressManager.checkCanceled()
                        ref.element.containingFile?.virtualFile?.let { callerFile ->
                            if (callerFile != seed) usageFiles.add(callerFile)
                        }
                        true // continue searching
                    }

                    if (usageFiles.isNotEmpty()) {
                        // INVERSE DOCUMENT FREQUENCY (IDF) SCORING: 40 / sqrt(N)
                        val idfScore = (40.0 / sqrt(usageFiles.size.toDouble())).toInt()
                        if (idfScore > 0) {
                            usageFiles.forEach { award(it, idfScore) }
                        }
                    }
                }
            }
        }

        // 8. Filter & Sort
        val projectDir = project.guessProjectDir()
        val dedupCache = contextState.getDedupCache()
        val validSuggestions = mutableSetOf<VirtualFile>()

        for ((file, score) in scores.entries.sortedByDescending { it.value }) {
            if (score < 15) continue
            if (validSuggestions.size >= 15) break // Cap tree size to prevent UI vertical clutter

            val currentLevel = contextState.getLevel(file)
            val maxLevel = ContextCapabilityUtil.getMaxLevel(file)

            // EXCLUSION: Disappear if it has reached its maximum capability
            if (currentLevel == maxLevel) continue

            // SMART OMISSION: Check if the AI already has the max level in its memory
            var isMaxLevelCached = false
            if (projectDir != null) {
                val relativePath = VfsUtilCore.getRelativePath(file, projectDir) ?: file.path
                val cachedRecord = dedupCache[relativePath]

                if (cachedRecord != null && cachedRecord.level == maxLevel) {
                    try {
                        val currentHash = readAction { contextState.getFileHash(project, file, maxLevel) }
                        if (currentHash == cachedRecord.hash) {
                            isMaxLevelCached = true
                        }
                    } catch (e: Exception) {
                        // Safely ignore
                    }
                }
            }

            if (!isMaxLevelCached) {
                validSuggestions.add(file)
            }
        }

        return validSuggestions
    }
}