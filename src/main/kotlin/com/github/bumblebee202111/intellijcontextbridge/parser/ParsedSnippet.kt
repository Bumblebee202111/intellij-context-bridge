package com.github.bumblebee202111.intellijcontextbridge.parser

data class ParsedSnippet(
    val filePath: String,
    val language: String,
    val code: String,
    val explanation: String? = null
)

object MarkdownResponseParser {

    /**
     * Parses an AI's response to extract code blocks and their associated file paths.
     * Supports both the new <tool_call><name>propose_edit</name> format and the legacy markdown headers.
     */
    fun parse(markdown: String): List<ParsedSnippet> {
        val snippets = mutableListOf<ParsedSnippet>()
        
        // 1. Extract XML tool calls (propose_edit)
        val toolCallRegex = Regex("<tool_call>(.*?)</tool_call>", RegexOption.DOT_MATCHES_ALL)
        for (match in toolCallRegex.findAll(markdown)) {
            val content = match.groupValues[1]
            val nameMatch = Regex("<name>(.*?)</name>", RegexOption.DOT_MATCHES_ALL).find(content)

            if (nameMatch?.groupValues?.get(1)?.trim() == "propose_edit") {
                val pathMatch = Regex("<path>(.*?)</path>", RegexOption.DOT_MATCHES_ALL).find(content)
                val explanationMatch = Regex("<explanation>(.*?)</explanation>", RegexOption.DOT_MATCHES_ALL).find(content)
                val codeMatch = Regex("<code>(.*?)</code>", RegexOption.DOT_MATCHES_ALL).find(content)

                if (pathMatch != null && codeMatch != null) {
                    val path = pathMatch.groupValues[1].trim()
                    val explanation = explanationMatch?.groupValues?.get(1)?.trim()
                    val rawCode = codeMatch.groupValues[1].trim()

                    // Clean up if the AI accidentally wrapped the code inside markdown ticks within the XML tag
                    val cleanCode = if (rawCode.startsWith("```")) {
                        rawCode.substringAfter("\n").substringBeforeLast("```").trim()
                    } else {
                        rawCode
                    }

                    val lang = path.substringAfterLast('.', "")
                    snippets.add(ParsedSnippet(path, lang, cleanCode, explanation))
                }
            }
        }

        // If we found valid XML tool calls, skip legacy markdown parsing to prevent duplicates
        if (snippets.isNotEmpty()) {
            return snippets
        }

        // 2. Fallback: Detect legacy file headers
        var currentFilePath = "Unknown File"
        var inCodeBlock = false
        var currentLang = ""
        val currentCode = StringBuilder()

        val lines = markdown.lines()
        
        for (line in lines) {
            // 1. Detect File Header
            // Matches: "### 📄 `app/src/main/MainActivity.kt`"
            if (line.startsWith("###") && line.contains("📄")) {
                currentFilePath = line.substringAfter("📄").replace("`", "").trim()
                continue
            }

            // 2. Detect Code Block Boundaries
            if (line.trim().startsWith("```")) {
                if (!inCodeBlock) {
                    // Start of code block
                    inCodeBlock = true
                    currentLang = line.trim().removePrefix("```").trim()
                    currentCode.clear()
                } else {
                    // End of code block
                    inCodeBlock = false
                    val codeContent = currentCode.toString().trimEnd()
                    
                    // Only add if it's not empty
                    if (codeContent.isNotBlank()) {
                        snippets.add(ParsedSnippet(currentFilePath, currentLang, codeContent))
                    }
                }
                continue
            }

            // 3. Capture Code
            if (inCodeBlock) {
                currentCode.appendLine(line)
            }
        }

        return snippets
    }
}