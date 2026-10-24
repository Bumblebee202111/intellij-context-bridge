package com.github.bumblebee202111.intellijcontextbridge.context.core

import com.github.bumblebee202111.intellijcontextbridge.state.UserTurn
import kotlinx.serialization.Serializable

@Serializable
data class AiAttachment(
    val name: String,
    val mimeType: String,
    val base64Data: String
)

@Serializable
data class AiPayload(
    val systemInstructions: String,
    val text: String,
    val attachments: List<AiAttachment>,
    val isCommit: Boolean = false
)

data class PayloadGenerationResult(
    val payload: AiPayload,
    val turn: UserTurn
)