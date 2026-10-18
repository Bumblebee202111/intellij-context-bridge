package com.github.bumblebee202111.intellijcontextbridge.context.core

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageGraphExtractor
import com.github.bumblebee202111.intellijcontextbridge.state.ContextLevel
import com.github.bumblebee202111.intellijcontextbridge.state.ContextState
import com.github.bumblebee202111.intellijcontextbridge.state.SeedGraphCacheEntry
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
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import com.intellij.psi.util.PsiModificationTracker
import kotlinx.coroutines.delay
import kotlin.math.sqrt

object ContextSuggestionEngine {

    suspend fun calculateSuggestions(project: Project, contextState: ContextState): Set<VirtualFile> {
        val scores = mutableMapOf<VirtualFile, Int>()
        val seedFiles = mutableSetOf<VirtualFile>()
        val uncachedSeeds = mutableListOf<VirtualFile>()
        val outgoingBySeed = mutableMapOf<VirtualFile, Set<VirtualFile>>()

        fun award(file: VirtualFile, points: Int) {
            if (!file.isValid || file.isDirectory || FileFilterUtil.isIgnored(project, file)) return
            scores[file] = (scores[file] ?: 0) + points
        }

        val psiManager = PsiManager.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)

        val psiModCount = readAction {
            PsiModificationTracker.getInstance(project).modificationCount
        }

        // Yielding Read Action: Collect seeds and resolve cached graphs or outgoing imports
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

            // 4. Expand Seeds with COMPLETE Context Files (including @-mentioned files)
            contextState.fileStates.forEach { (file, level) ->
                if (level == ContextLevel.COMPLETE && file.isValid && !file.isDirectory) {
                    seedFiles.add(file)
                }
            }

            for (seed in seedFiles) {
                ProgressManager.checkCanceled()
                val cachedGraph = contextState.getCachedSeedGraph(seed, psiModCount)
                if (cachedGraph != null) {
                    cachedGraph.outgoingFiles.forEach { award(it, 15) }
                    cachedGraph.incomingScores.forEach { (callerFile, idfScore) -> award(callerFile, idfScore) }
                } else {
                    uncachedSeeds.add(seed)
                }
            }

            // 5. Outgoing Dependencies (only for uncached seeds)
            if (uncachedSeeds.isNotEmpty()) {
                val filenamesByShortName = FilenameIndex.getAllFilenames(project)
                    .groupBy { it.substringBeforeLast('.') }

                for (seed in uncachedSeeds) {
                    ProgressManager.checkCanceled()
                    val psiFile = psiManager.findFile(seed) ?: continue

                    val extractor =
                        LanguageGraphExtractor.Companion.EP_NAME.extensionList.firstOrNull { it.isSupported(psiFile) }
                    val importedNames = extractor?.getImportedNames(psiFile) ?: emptySet()

                    val seedOutgoing = mutableSetOf<VirtualFile>()
                    for (importedName in importedNames) {
                        ProgressManager.checkCanceled()
                        val matchingFilenames = filenamesByShortName[importedName] ?: continue
                        for (name in matchingFilenames) {
                            FilenameIndex.getVirtualFilesByName(name, projectScope).forEach { importedFile ->
                                seedOutgoing.add(importedFile)
                                award(importedFile, 15)
                            }
                        }
                    }
                    outgoingBySeed[seed] = seedOutgoing
                }
            }
        }

        // 6. Unbounded Graph Traversal (Incoming Usages for uncached seeds)
        for (seed in uncachedSeeds) {
            val primaryElements = readAction {
                if (!seed.isValid) return@readAction emptyList<PsiElement>()

                val psiFile = psiManager.findFile(seed) ?: return@readAction emptyList<PsiElement>()
                val extractor =
                    LanguageGraphExtractor.Companion.EP_NAME.extensionList.firstOrNull { it.isSupported(psiFile) }
                extractor?.getPrimaryDeclarations(psiFile) ?: emptyList()
            }

            val seedIncomingScores = mutableMapOf<VirtualFile, Int>()

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
                            usageFiles.forEach { callerFile ->
                                seedIncomingScores[callerFile] = (seedIncomingScores[callerFile] ?: 0) + idfScore
                                award(callerFile, idfScore)
                            }
                        }
                    }
                }
            }

            contextState.cacheSeedGraph(
                seed,
                SeedGraphCacheEntry(
                    psiModificationCount = psiModCount,
                    outgoingFiles = outgoingBySeed[seed] ?: emptySet(),
                    incomingScores = seedIncomingScores
                )
            )
        }

        // 7. Filter & Sort
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
                        val currentHash =
                            readAction { PayloadGenerator.getFileHash(project, contextState, file, maxLevel) }
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