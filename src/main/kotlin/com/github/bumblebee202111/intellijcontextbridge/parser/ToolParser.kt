package com.github.bumblebee202111.intellijcontextbridge.parser

object ToolParser {

    private val TOOL_BLOCK_REGEX = Regex(
        "```(?:xml)?\\s*<ide:(read_file|propose_edit|delete_file|rename_file|fill_commit_message)>(.*?)</ide:\\1>\\s*```",
        RegexOption.DOT_MATCHES_ALL
    )
    private val PATH_REGEX = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL)

    fun stripToolBlocks(markdown: String): String {
        return TOOL_BLOCK_REGEX.replace(markdown, "")
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()
    }

    /**
     * Parses an AI's response to extract unified tool calls.
     */
    fun parse(markdown: String): List<AgentTool> {
        val tools = mutableListOf<AgentTool>()

        for (match in TOOL_BLOCK_REGEX.findAll(markdown)) {
            val toolName = match.groupValues[1]
            val content = match.groupValues[2]

            when (toolName) {
                "read_file" -> {
                    val reason = extractTag(content, "reason") ?: ""
                    val pathsBlock = extractTag(content, "paths")
                    val paths = mutableListOf<String>()

                    if (pathsBlock != null) {
                        for (pathMatch in PATH_REGEX.findAll(pathsBlock)) {
                            paths.add(pathMatch.groupValues[1].trim())
                        }
                        if (paths.isEmpty()) {
                            paths.addAll(
                                pathsBlock.split("\n", ",")
                                    .map { it.trim() }
                                    .filter { it.isNotBlank() }
                            )
                        }
                    }

                    tools.add(ReadFileTool(paths, reason))
                }
                "propose_edit" -> {
                    val path = extractTag(content, "path")
                    val explanation = extractTag(content, "explanation") ?: ""
                    var rawCode = extractTag(content, "code", greedy = true)

                    if (path != null && rawCode != null) {
                        if (rawCode.startsWith("<![CDATA[")) {
                            rawCode = rawCode.substringAfter("<![CDATA[").substringBeforeLast("]]>").trim()
                        }
                        val lang = path.substringAfterLast('.', "")
                        tools.add(EditFileTool(path, lang, rawCode, explanation))
                    }
                }
                "delete_file" -> {
                    val path = extractTag(content, "path")
                    val explanation = extractTag(content, "explanation") ?: ""
                    if (path != null) {
                        tools.add(DeleteFileTool(path, explanation))
                    }
                }
                "rename_file" -> {
                    val sourcePath = extractTag(content, "source_path")
                    val targetPath = extractTag(content, "target_path")
                    val explanation = extractTag(content, "explanation") ?: ""
                    if (sourcePath != null && targetPath != null) {
                        tools.add(RenameFileTool(sourcePath, targetPath, explanation))
                    }
                }
                "fill_commit_message" -> {
                    val message = extractTag(content, "message").orEmpty()
                    if (message.isNotBlank()) {
                        tools.add(FillCommitMessageTool(message))
                    }
                }
            }
        }

        return tools
    }

    private fun extractTag(content: String, tag: String, greedy: Boolean = false): String? {
        val openTag = "<$tag>"
        val closeTag = "</$tag>"
        val startIndex = content.indexOf(openTag)
        if (startIndex == -1) return null
        val contentStart = startIndex + openTag.length
        val endIndex = if (greedy) {
            content.lastIndexOf(closeTag)
        } else {
            content.indexOf(closeTag, contentStart)
        }
        if (endIndex < contentStart) return null
        return content.substring(contentStart, endIndex).trim()
    }
}