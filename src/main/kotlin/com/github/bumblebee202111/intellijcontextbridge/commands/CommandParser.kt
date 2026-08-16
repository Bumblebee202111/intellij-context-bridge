package com.github.bumblebee202111.intellijcontextbridge.commands

import com.github.bumblebee202111.intellijcontextbridge.context.IntentMode

object CommandParser {

    private val FRONTMATTER_REGEX = Regex("(?s)^---\\n(.*?)\\n---\\n(.*)")

    fun parse(content: String, fallbackName: String = ""): SlashCommand? {
        val normalizedContent = content.replace("\r\n", "\n")
        val match = FRONTMATTER_REGEX.find(normalizedContent) ?: return null

        val frontmatter = match.groupValues[1]
        val promptBody = match.groupValues[2]

        var name = fallbackName
        var description = ""
        var mode = IntentMode.ASK
        var contextAction = CommandContextAction.ADDITIVE
        val includeFiles = mutableListOf<String>()

        var readingIncludeFiles = false

        for (line in frontmatter.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            if (trimmed.startsWith("-") && readingIncludeFiles) {
                val file = trimmed.removePrefix("-").trim().removeSurrounding("\"").removeSurrounding("'")
                if (file.isNotEmpty()) {
                    includeFiles.add(file)
                }
                continue
            }

            if (trimmed.contains(":")) {
                readingIncludeFiles = false
                val key = trimmed.substringBefore(":").trim()
                val value = trimmed.substringAfter(":").trim().removeSurrounding("\"").removeSurrounding("'")

                when (key) {
                    "name" -> if (value.isNotBlank()) name = value
                    "description" -> description = value
                    "mode" -> {
                        mode = if (value.equals("EDIT", ignoreCase = true)) IntentMode.EDIT else IntentMode.ASK
                    }
                    "context-action" -> {
                        contextAction = if (value.equals("REPLACE", ignoreCase = true)) CommandContextAction.REPLACE else CommandContextAction.ADDITIVE
                    }
                    "include-files" -> readingIncludeFiles = true
                }
            }
        }

        if (name.isBlank()) return null

        return SlashCommand(
            name = name,
            description = description,
            mode = mode,
            contextAction = contextAction,
            includeFiles = includeFiles,
            promptBody = promptBody
        )
    }
}