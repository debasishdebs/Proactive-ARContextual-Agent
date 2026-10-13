package com.rokid.context.connectors.tools

import kotlinx.serialization.json.JsonObject

/** R-63 stub: Settings shows "MCP servers · Coming in v2". Never registered in ActionsGraph. */
class McpConnector : ToolConnector {
    override suspend fun listTools(): List<ToolSpec> = emptyList()
    override suspend fun invoke(name: String, args: JsonObject): JsonObject = throw UnsupportedOperationException("MCP is not available in v1")
}
