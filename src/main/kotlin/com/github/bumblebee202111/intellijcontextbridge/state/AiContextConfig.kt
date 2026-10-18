package com.github.bumblebee202111.intellijcontextbridge.state

import kotlinx.serialization.Serializable

@Serializable
data class AiContextConfig(
    val skeleton: List<String> = emptyList(),
    val complete: List<String> = emptyList()
)