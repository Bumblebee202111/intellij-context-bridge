package com.github.bumblebee202111.intellijcontextbridge.parser

data class ParsedSnippet(
    val filePath: String,
    val language: String,
    val code: String
)

object MarkdownResponseParser {

    /**
     * Parses an AI's Markdown response to extract code blocks and their associated file paths.
     * Expects headers in the format: `### 📄 path/to/file.kt`
     */
    fun parse(markdown: String): List<ParsedSnippet> {
        val snippets = mutableListOf<ParsedSnippet>()
        
        var currentFilePath = "Unknown File" // Fallback if AI forgets the header
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