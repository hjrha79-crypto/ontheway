package com.vita.ontheway

object ContextManager {
    private val agentContexts = mutableMapOf<String, Map<String, Any>>()

    fun updateAgent(agentName: String, context: Map<String, Any>) {
        agentContexts[agentName] = context
        android.util.Log.d("ContextManager", "$agentName 업데이트: $context")
    }

    fun getAgent(agentName: String): Map<String, Any>? =
        agentContexts[agentName]
}
