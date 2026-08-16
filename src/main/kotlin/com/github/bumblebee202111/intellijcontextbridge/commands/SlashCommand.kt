package com.github.bumblebee202111.intellijcontextbridge.commands

import com.github.bumblebee202111.intellijcontextbridge.context.IntentMode

enum class CommandContextAction {
    ADDITIVE, REPLACE
}

data class SlashCommand(
    val name: String,
    val description: String,
    val mode: IntentMode,
    val contextAction: CommandContextAction,
    val includeFiles: List<String>,
    val promptBody: String
)