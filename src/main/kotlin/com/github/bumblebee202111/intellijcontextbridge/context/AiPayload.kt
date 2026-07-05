package com.github.bumblebee202111.intellijcontextbridge.context

import com.intellij.openapi.vfs.VirtualFile
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

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
) {
    @Transient
    var dedupedFiles: Set<VirtualFile> = emptySet()
}