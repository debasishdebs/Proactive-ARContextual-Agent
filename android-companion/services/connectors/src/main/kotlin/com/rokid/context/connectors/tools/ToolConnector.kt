package com.rokid.context.connectors.tools

import kotlinx.serialization.json.JsonObject

/** R-63: a source of model-callable tools (v2: MCP servers). v1 registers none. */
interface ToolConnector {
    suspend fun listTools(): List<ToolSpec>
    suspend fun invoke(name: String, args: JsonObject): JsonObject
}

data class ToolSpec(val name: String, val description: String, val inputSchema: JsonObject)
