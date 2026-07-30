package com.github.bumblebee202111.intellijcontextbridge.parser

data class ParsedToolCall(
    val name: String,
    val paths: List<String>,
    val reason: String
)

object ToolCallParser {
    fun parse(markdown: String): ParsedToolCall? {
        if (!markdown.contains("<tool_call>")) return null

        val nameMatch = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(markdown)
        val reasonMatch = Regex("<reason>(.*?)</reason>", RegexOption.DOT_MATCHES_ALL).find(markdown)

        val name = nameMatch?.groupValues?.get(1)?.trim() ?: return null
        val reason = reasonMatch?.groupValues?.get(1)?.trim() ?: ""

        val paths = mutableListOf<String>()
        val pathsBlockMatch = Regex("<paths>(.*?)</paths>", RegexOption.DOT_MATCHES_ALL).find(markdown)
        
        if (pathsBlockMatch != null) {
            val pathMatches = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).findAll(pathsBlockMatch.groupValues[1])
            for (match in pathMatches) {
                paths.add(match.groupValues[1].trim())
            }
        }

        return ParsedToolCall(name, paths, reason)
    }
}