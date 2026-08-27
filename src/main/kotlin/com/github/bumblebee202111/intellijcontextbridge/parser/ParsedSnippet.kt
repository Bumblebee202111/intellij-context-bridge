package com.github.bumblebee202111.intellijcontextbridge.parser

sealed interface AgentAction {
    val explanation: String?
}

data class EditAction(
    val filePath: String,
    val language: String,
    val code: String,
    override val explanation: String? = null
) : AgentAction

data class DeleteAction(
    val filePath: String,
    override val explanation: String? = null
) : AgentAction

data class RenameAction(
    val sourcePath: String,
    val targetPath: String,
    override val explanation: String? = null
) : AgentAction

object MarkdownResponseParser {

    /**
     * Parses an AI's response to extract tool actions and legacy code blocks.
     */
    fun parse(markdown: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        
        // 1. Extract XML tool calls (propose_edit, delete_file, rename_file)
        val toolCallRegex = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
        for (match in toolCallRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val nameMatch = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(content)
            val name = nameMatch?.groupValues?.get(1)?.trim() ?: continue
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            when (name) {
                "propose_edit" -> {
                    val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
                    val rawCode = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

                    if (path != null && rawCode != null) {
                        val cleanCode = if (rawCode.startsWith("```")) {
                            rawCode.substringAfter("\n").substringBeforeLast("```").trim()
                        } else rawCode
                        val lang = path.substringAfterLast('.', "")
                        actions.add(EditAction(path, lang, cleanCode, explanation))
                    }
                }
                "delete_file" -> {
                    val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
                    if (path != null) actions.add(DeleteAction(path, explanation))
                }
                "rename_file" -> {
                    val sourcePath = Regex("<source_path>(.*?)</source_path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
                    val targetPath = Regex("<target_path>(.*?)</target_path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
                    if (sourcePath != null && targetPath != null) actions.add(RenameAction(sourcePath, targetPath, explanation))
                }
            }
        }

        // If we found valid XML tool calls, skip legacy markdown parsing to prevent duplicates
        if (actions.isNotEmpty()) {
            return actions
        }

        // 2. Fallback: Detect legacy file headers
        var currentFilePath = "Unknown File"
        var inCodeBlock = false
        var currentLang = ""
        val currentCode = StringBuilder()

        val lines = markdown.lines()
        
        for (line in lines) {
            // Matches: "### 📄 `app/src/main/MainActivity.kt`"
            if (line.startsWith("###") && line.contains("📄")) {
                currentFilePath = line.substringAfter("📄").replace("`", "").trim()
                continue
            }

            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    inCodeBlock = true
                    currentLang = line.trim().removePrefix("```").trim()
                    currentCode.clear()
                } else {
                    inCodeBlock = false
                    val codeContent = currentCode.toString().trimEnd()

                    if (codeContent.isNotBlank()) {
                        actions.add(EditAction(currentFilePath, currentLang, codeContent))
                    }
                }
                continue
            }

            if (inCodeBlock) {
                currentCode.appendLine(line)
            }
        }

        return actions
    }
}