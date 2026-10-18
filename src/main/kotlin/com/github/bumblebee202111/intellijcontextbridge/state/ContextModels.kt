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
    var hash: String = ""
)

data class UserTurn(
    var id: String = UUID.randomUUID().toString(),
    var timestamp: Long = System.currentTimeMillis(),
    var prompt: String = "",
    var sentFiles: MutableMap<String, FileStateRecord> = mutableMapOf()
)