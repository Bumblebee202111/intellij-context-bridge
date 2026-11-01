package com.github.bumblebee202111.intellijcontextbridge.state

import java.util.UUID

enum class IntentMode {
    ASK, EDIT
}

enum class ContextLevel {
    NONE, SKELETON, COMPLETE, MIXED
}

data class FileStateRecord(
    var level: ContextLevel = ContextLevel.NONE,
    var hash: String = "",
    var wasDeduped: Boolean = false
)

data class UserTurn(
    var id: String = UUID.randomUUID().toString(),
    var timestamp: Long = System.currentTimeMillis(),
    var prompt: String = "",
    var mode: IntentMode = IntentMode.ASK,
    var sentFiles: MutableMap<String, FileStateRecord> = mutableMapOf()
)

data class ModelTurn(
    var id: String = UUID.randomUUID().toString(),
    var timestamp: Long = System.currentTimeMillis(),
    var markdown: String = ""
)