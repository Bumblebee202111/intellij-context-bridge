package com.github.bumblebee202111.intellijcontextbridge.parser

sealed interface AgentTool {
    val explanation: String

    sealed interface Read : AgentTool
    sealed interface Mutation : AgentTool
}

data class ReadFileTool(
    val paths: List<String>,
    override val explanation: String
) : AgentTool.Read

data class EditFileTool(
    val filePath: String,
    val language: String,
    val code: String,
    override val explanation: String
) : AgentTool.Mutation

data class DeleteFileTool(
    val filePath: String,
    override val explanation: String
) : AgentTool.Mutation

data class RenameFileTool(
    val sourcePath: String,
    val targetPath: String,
    override val explanation: String
) : AgentTool.Mutation

object ToolParser {

    /**
     * Parses an AI's response to extract unified tool calls.
     */
    fun parse(markdown: String): List<AgentTool> {
        val tools = mutableListOf<AgentTool>()

        // 1. Parse ide:read_file
        val readFileRegex = Regex("```(?:xml)?\\s*<ide:read_file>(.*?)</ide:read_file>\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (match in readFileRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val reason = Regex("<reason>(.*?)</reason>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim() ?: ""

            val paths = mutableListOf<String>()
            val pathsBlockMatch = Regex("<paths>(.*?)</paths>", RegexOption.DOT_MATCHES_ALL).find(content)

            if (pathsBlockMatch != null) {
                val pathMatches = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).findAll(pathsBlockMatch.groupValues[1])
                for (pathMatch in pathMatches) {
                    paths.add(pathMatch.groupValues[1].trim())
                }

                if (paths.isEmpty()) {
                    val rawPaths = pathsBlockMatch.groupValues[1].split("\n", ",").map { it.trim() }.filter { it.isNotBlank() }
                    paths.addAll(rawPaths)
                }
            }

            tools.add(ReadFileTool(paths, reason))
        }

        // 2. Parse ide:propose_edit
        val editRegex = Regex("```(?:xml)?\\s*<ide:propose_edit>(.*?)</ide:propose_edit>\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (match in editRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim() ?: ""
            var rawCode = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()

            if (path != null && rawCode != null) {
                if (rawCode.startsWith("<![CDATA[")) {
                    rawCode = rawCode.substringAfter("<![CDATA[").substringBeforeLast("]]>").trim()
                }
                val lang = path.substringAfterLast('.', "")
                tools.add(EditFileTool(path, lang, rawCode, explanation))
            }
        }

        // 3. Parse ide:delete_file
        val deleteRegex = Regex("```(?:xml)?\\s*<ide:delete_file>(.*?)</ide:delete_file>\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (match in deleteRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val path = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim() ?: ""

            if (path != null) tools.add(DeleteFileTool(path, explanation))
        }

        // 4. Parse ide:rename_file
        val renameRegex = Regex("```(?:xml)?\\s*<ide:rename_file>(.*?)</ide:rename_file>\\s*```", RegexOption.DOT_MATCHES_ALL)
        for (match in renameRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val sourcePath = Regex("<source_path>(.*?)</source_path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val targetPath = Regex("<target_path>(.*?)</target_path>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim()
            val explanation = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)?.groupValues?.get(1)?.trim() ?: ""

            if (sourcePath != null && targetPath != null) tools.add(RenameFileTool(sourcePath, targetPath, explanation))
        }

        return tools
    }
}