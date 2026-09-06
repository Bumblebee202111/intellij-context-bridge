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
     * Parses an AI's response to extract tool actions.
     */
    fun parse(markdown: String): List<AgentAction> {
        val actions = mutableListOf<AgentAction>()
        
        // 1. Parse ide:propose_edit\
        val editRegex = Regex("```(?:xml)?\\s*<ide:propose_edit>(.*?)</ide:propose_edit>\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (match in editRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val rawCode = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            if (path != null && rawCode != null) {
                val cleanCode = if (rawCode.startsWith("```")) {
                    rawCode.substringAfter("\n").substringBeforeLast("```").trim()
                } else rawCode
                val lang = path.substringAfterLast('.', "")
                actions.add(EditAction(path, lang, cleanCode, explanation))
            }
        }

        // 2. Parse delete_file
        val deleteRegex = Regex("<delete_file>(.*?)</delete_file>", RegexOption.DOT_MATCHES_ALL)
        for (match in deleteRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            if (path != null) actions.add(DeleteAction(path, explanation))
        }

        // 3. Parse rename_file
        val renameRegex = Regex("<rename_file>(.*?)</rename_file>", RegexOption.DOT_MATCHES_ALL)
        for (match in renameRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val sourcePath = Regex("<source_path>(.*?)</source_path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val targetPath = Regex("<target_path>(.*?)</target_path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            if (sourcePath != null && targetPath != null) actions.add(RenameAction(sourcePath, targetPath, explanation))
        }

        return actions
    }
}