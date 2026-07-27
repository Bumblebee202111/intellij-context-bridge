package com.github.bumblebee202111.intellijcontextbridge.services

import com.intellij.openapi.components.Service
import kotlinx.coroutines.CoroutineScope

/**
 * A dedicated service to safely receive a platform-managed CoroutineScope.
 * This avoids the classloader crashes caused by manually instantiating CoroutineScope
 * or using Dispatchers.Default in IntelliJ plugins.
 */
@Service(Service.Level.PROJECT)
class ContextCoroutineScopeService(val scope: CoroutineScope)