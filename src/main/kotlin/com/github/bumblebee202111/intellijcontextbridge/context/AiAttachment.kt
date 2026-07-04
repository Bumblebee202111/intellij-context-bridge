package com.github.bumblebee202111.intellijcontextbridge.context

import kotlinx.serialization.Serializable

@Serializable
data class AiAttachment(
    val name: String,
    val mimeType: String,
    val base64Data: String
)

@Serializable
data class AiPayload(
    val text: String,
    val attachments: List<AiAttachment>
)