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

data class FillCommitMessageTool(
    val message: String,
    override val explanation: String = ""
) : AgentTool.Mutation