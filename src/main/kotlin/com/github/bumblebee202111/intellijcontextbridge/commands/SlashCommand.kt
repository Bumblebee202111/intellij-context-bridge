package com.github.bumblebee202111.intellijcontextbridge.commands

import com.github.bumblebee202111.intellijcontextbridge.state.IntentMode
import kotlinx.serialization.Serializable

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

@Serializable
data class SyncCommand(
    val name: String,
    val mode: String,
    val promptBody: String
)