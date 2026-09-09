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

object AgentActionParser {

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
            var rawCode = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            if (path != null && rawCode != null) {
                if (rawCode.startsWith("<![CDATA[")) {
                    rawCode = rawCode.substringAfter("<![CDATA[").substringBeforeLast("]]>").trim()
                }
                val lang = path.substringAfterLast('.', "")
                actions.add(EditAction(path, lang, rawCode, explanation))
            }
        }

        // 2. Parse ide:delete_file
        val deleteRegex = Regex("```(?:xml)?\\s*<ide:delete_file>(.*?)</ide:delete_file>\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (match in deleteRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            if (path != null) actions.add(DeleteAction(path, explanation))
        }

        // 3. Parse ide:rename_file
        val renameRegex = Regex("```(?:xml)?\\s*<ide:rename_file>(.*?)</ide:rename_file>\\s*```", RegexOption.DOT_MATCHES_ALL)
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