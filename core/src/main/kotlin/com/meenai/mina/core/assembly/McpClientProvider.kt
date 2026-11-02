package com.meenai.mina.core.assembly

import com.meenai.mina.core.cap.mcp.McpCapability
import com.meenai.mina.core.cap.mcp.McpConnection
import com.meenai.mina.core.run.RunSpec

internal class McpClientProvider(private val connection: McpConnection) : CapabilityProvider {
    override val id: String = "mcp:${connection.endpoint.serverId}"
    override fun open(spec: RunSpec) = McpCapability(connection, id)
}
