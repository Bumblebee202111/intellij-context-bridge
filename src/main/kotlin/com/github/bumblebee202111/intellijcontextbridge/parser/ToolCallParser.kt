package com.github.bumblebee202111.intellijcontextbridge.parser

data class ParsedToolCall(
    val name: String,
    val paths: List<String>,
    val reason: String
)

object ToolCallParser {
    fun parse(markdown: String): List<ParsedToolCall> {
        val results = mutableListOf<ParsedToolCall>()

        val readFileRegex = Regex("```(?:xml)?\\s*<ide:read_file>(.*?)</ide:read_file>\\s*```", RegexOption.DOT_MATCHES_ALL)

        for (match in readFileRegex.findAll(markdown)) {
            val content = match.groupValues[1]

            val reasonMatch = Regex("<reason>(.*?)</reason>", RegexOption.DOT_MATCHES_ALL).find(content)
            val reason = reasonMatch?.groupValues?.get(1)?.trim() ?: ""

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

            results.add(ParsedToolCall("read_file", paths, reason))
        }

        return results
    }
}