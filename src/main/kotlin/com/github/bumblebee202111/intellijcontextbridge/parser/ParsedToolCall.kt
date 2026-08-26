package com.github.bumblebee202111.intellijcontextbridge.parser

data class ParsedToolCall(
    val name: String,
    val paths: List<String>,
    val reason: String
)

object ToolCallParser {
    fun parse(markdown: String): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()
        val toolCallRegex = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)

        for (match in toolCallRegex.findAll(markdown)) {
            val content = match.groupValues[1]

            val nameMatch = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(content)
            val name = nameMatch?.groupValues?.get(1)?.trim() ?: continue

            val reasonMatch = Regex("<reason>(.*?)</reason>", RegexOption.DOT_MATCHES_ALL).find(content)
            val reason = reasonMatch?.groupValues?.get(1)?.trim() ?: ""

            val paths = mutableListOf<String>()
            val pathsBlockMatch = Regex("<paths>(.*?)</paths>", RegexOption.DOT_MATCHES_ALL).find(content)

            if (pathsBlockMatch != null) {
                val pathMatches = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).findAll(pathsBlockMatch.groupValues[1])
                for (pathMatch in pathMatches) {
                    paths.add(pathMatch.groupValues[1].trim())
                }
            }

            results.add(ParsedToolCall(name, paths, reason))
        }

        return results
    }
}