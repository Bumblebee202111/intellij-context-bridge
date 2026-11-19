package com.github.bumblebee202111.intellijcontextbridge.server

import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

class ContextBridgeStartupActivity : ProjectActivity {
    override suspend fun execute(project: Project) {
        service<ContextBridgeServer>().start()
    }
}