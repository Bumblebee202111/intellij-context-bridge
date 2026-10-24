package com.github.bumblebee202111.intellijcontextbridge.context.core

import com.github.bumblebee202111.intellijcontextbridge.context.ep.LanguageGraphExtractor
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
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.search.searches.ReferencesSearch
import kotlinx.coroutines.delay
import kotlin.math.sqrt

object ContextSuggestionEngine {

    private fun isEligible(project: Project, file: VirtualFile): Boolean {
        return file.isValid && !file.isDirectory && !FileFilterUtil.isIgnored(project, file)
    }

    suspend fun calculateSuggestions(project: Project, contextState: ContextState): Set<VirtualFile> {
        val scores = mutableMapOf<VirtualFile, Int>()

        fun addScore(file: VirtualFile, points: Int) {
            scores[file] = (scores[file] ?: 0) + points
        }

        val psiManager = PsiManager.getInstance(project)
        val projectScope = GlobalSearchScope.projectScope(project)

        // 1. Fast Read Action: Collect seeds and apply already-cached graphs
        val (initialScores, uncachedSeeds) = readAction {
            val localScores = mutableMapOf<VirtualFile, Int>()
            val seedFiles = mutableSetOf<VirtualFile>()
            val localUncached = mutableListOf<VirtualFile>()

            fun awardLocal(file: VirtualFile, points: Int) {
                if (!isEligible(project, file)) return
                localScores[file] = (localScores[file] ?: 0) + points
            }

            val fileEditorManager = FileEditorManager.getInstance(project)
            val activeFiles = fileEditorManager.selectedFiles.toSet()
            activeFiles.forEach {
                awardLocal(it, 50)
                if (isEligible(project, it)) seedFiles.add(it)
            }

            fileEditorManager.openFiles.forEach {
                if (!activeFiles.contains(it)) {
                    awardLocal(it, 20)
                    if (isEligible(project, it)) seedFiles.add(it)
                }
            }

            ChangeListManager.getInstance(project).affectedFiles.forEach {
                awardLocal(it, 40)
                if (isEligible(project, it)) seedFiles.add(it)
            }

            contextState.fileStates.forEach { (file, level) ->
                if (level == ContextLevel.COMPLETE && isEligible(project, file)) {
                    seedFiles.add(file)
                }
            }

            for (seed in seedFiles) {
                ProgressManager.checkCanceled()
                val cachedGraph = contextState.getCachedSeedGraph(seed)
                if (cachedGraph != null) {
                    cachedGraph.outgoingFiles.forEach { awardLocal(it, 15) }
                    cachedGraph.incomingScores.forEach { (callerFile, idfScore) -> awardLocal(callerFile, idfScore) }
                } else {
                    localUncached.add(seed)
                }
            }

            localScores to localUncached
        }

        initialScores.forEach { (file, pts) -> addScore(file, pts) }

        // 2. Pre-index project filenames only if there are uncached seeds
        val filenamesByShortName = if (uncachedSeeds.isNotEmpty()) {
            readAction {
                FilenameIndex.getAllFilenames(project).groupBy { it.substringBeforeLast('.') }
            }
        } else {
            emptyMap()
        }

        // 3. Granular Per-Seed Graph Traversal (Outgoing + Incoming saved per seed)
        for (seed in uncachedSeeds) {
            val (seedOutgoing, primaryElements) = readAction {
                if (!seed.isValid) return@readAction emptySet<VirtualFile>() to emptyList<PsiElement>()
                val psiFile = psiManager.findFile(seed)
                    ?: return@readAction emptySet<VirtualFile>() to emptyList<PsiElement>()

                val extractor =
                    LanguageGraphExtractor.Companion.EP_NAME.extensionList.firstOrNull { it.isSupported(psiFile) }
                val importedNames = extractor?.getImportedNames(psiFile) ?: emptySet()
                val outgoing = mutableSetOf<VirtualFile>()

                for (importedName in importedNames) {
                    ProgressManager.checkCanceled()
                    val matchingFilenames = filenamesByShortName[importedName] ?: continue
                    for (name in matchingFilenames) {
                        FilenameIndex.getVirtualFilesByName(name, projectScope).forEach { importedFile ->
                            if (isEligible(project, importedFile)) {
                                outgoing.add(importedFile)
                            }
                        }
                    }
                }

                val declarations = extractor?.getPrimaryDeclarations(psiFile) ?: emptyList()
                outgoing to declarations
            }

            seedOutgoing.forEach { addScore(it, 15) }
            val seedIncomingScores = mutableMapOf<VirtualFile, Int>()

            for (element in primaryElements) {
                delay(10)

                val elementUsageFiles = readAction {
                    if (!element.isValid) return@readAction emptySet<VirtualFile>()

                    val usageFiles = mutableSetOf<VirtualFile>()

                    ReferencesSearch.search(element, projectScope).forEach { ref ->
                        ProgressManager.checkCanceled()
                        ref.element.containingFile?.virtualFile?.let { callerFile ->
                            if (callerFile != seed && isEligible(project, callerFile)) {
                                usageFiles.add(callerFile)
                            }
                        }
                        true
                    }

                    usageFiles
                }

                if (elementUsageFiles.isNotEmpty()) {
                    val idfScore = (40.0 / sqrt(elementUsageFiles.size.toDouble())).toInt()
                    if (idfScore > 0) {
                        elementUsageFiles.forEach { callerFile ->
                            seedIncomingScores[callerFile] = (seedIncomingScores[callerFile] ?: 0) + idfScore
                            addScore(callerFile, idfScore)
                        }
                    }
                }
            }

            if (seed.isValid) {
                contextState.cacheSeedGraph(seed, seedOutgoing, seedIncomingScores)
            }
        }

        // 4. Filter & Sort
        val projectDir = readAction { project.guessProjectDir() }
        val dedupCache = contextState.getDedupCache()
        val validSuggestions = mutableSetOf<VirtualFile>()

        for ((file, score) in scores.entries.sortedByDescending { it.value }) {
            if (score < 15) continue
            if (validSuggestions.size >= 15) break // Cap tree size to prevent UI vertical clutter

            val shouldInclude = readAction {
                ProgressManager.checkCanceled()
                if (!file.isValid) return@readAction false

                val currentLevel = contextState.getLevel(file)
                val maxLevel = ContextCapabilityUtil.getMaxLevel(file)

                // EXCLUSION: Disappear if it has reached its maximum capability
                if (currentLevel == maxLevel) return@readAction false

                // SMART OMISSION: Check if the AI already has the max level in its memory
                !PayloadGenerator.isFileDeduped(project, projectDir, contextState, file, maxLevel, dedupCache)
            }

            if (shouldInclude) {
                validSuggestions.add(file)
            }
        }

        return validSuggestions
    }
}