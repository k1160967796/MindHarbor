package com.mindbridge.agent.service.mcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;

public class McpToolClient implements AutoCloseable {
    private String remoteUrl;
    private String token;
    private io.modelcontextprotocol.client.McpSyncClient remote;

    private final ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider;
    private final ObjectMapper objectMapper;

    public McpToolClient(
            ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider,
            ObjectMapper objectMapper
    ) {
        this.toolCallbackProvider = toolCallbackProvider;
        this.objectMapper = objectMapper;
    }

    public String call(String toolName, Map<String, Object> arguments) {
        if (remoteUrl != null) {
            var client = connection();
            io.modelcontextprotocol.spec.McpSchema.CallToolResult result;
            try { result=client.callTool(new io.modelcontextprotocol.spec.McpSchema.CallToolRequest(toolName, arguments)); }
            catch(RuntimeException ex) {
                invalidate(client); // Reconnect on the next durable attempt; do not send again inside this call.
                throw ex;
            }
            if (Boolean.TRUE.equals(result.isError())) throw new IllegalStateException("MCP tool failed: " + toolName);
            return result.content().stream()
                    .filter(c -> c instanceof io.modelcontextprotocol.spec.McpSchema.TextContent)
                    .map(c -> ((io.modelcontextprotocol.spec.McpSchema.TextContent)c).text())
                    .findFirst().orElseThrow(() -> new IllegalStateException("Empty MCP result"));
        }
        ToolCallback callback = findTool(toolName);
        try {
            return callback.call(objectMapper.writeValueAsString(arguments));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Failed to serialize MCP tool arguments for " + toolName, exception);
        }
    }

    public McpToolClient remote(String url, String token) {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("MCP service token is required");
        this.remoteUrl=url; this.token=token; return this;
    }
    private synchronized io.modelcontextprotocol.client.McpSyncClient connection() {
        if(remote != null) return remote;
        var transport=io.modelcontextprotocol.client.transport.WebFluxSseClientTransport.builder(
                org.springframework.web.reactive.function.client.WebClient.builder().baseUrl(remoteUrl)
                .defaultHeader("Authorization", "Bearer " + token)).sseEndpoint("/sse").build();
        var client=io.modelcontextprotocol.client.McpClient.sync(transport)
                .requestTimeout(java.time.Duration.ofSeconds(30)).initializationTimeout(java.time.Duration.ofSeconds(15))
                .clientInfo(new io.modelcontextprotocol.spec.McpSchema.Implementation("mindbridge-business", "0.2.0")).build();
        try {client.initialize(); remote=client; return client;}
        catch(RuntimeException ex) {client.closeGracefully(); throw ex;}
    }
    public com.fasterxml.jackson.databind.JsonNode callJson(String name, Map<String,Object> arguments) {
        try {
            var result=objectMapper.readTree(call(name,arguments));
            if(!result.isObject() || !result.hasNonNull("status")) throw new IllegalStateException("Invalid MCP result: "+name);
            return result;
        } catch(java.io.IOException ex) {throw new IllegalStateException("Invalid MCP JSON: "+name, ex);}
    }
    @Override public synchronized void close() {if(remote!=null){remote.closeGracefully();remote=null;}}
    private synchronized void invalidate(io.modelcontextprotocol.client.McpSyncClient failed) {
        if(remote==failed){remote=null;try{failed.closeGracefully();}catch(RuntimeException ignored){}}
    }

    private ToolCallback findTool(String toolName) {
        SyncMcpToolCallbackProvider mcpToolCallbackProvider = toolCallbackProvider.getIfAvailable(() -> {
            throw new IllegalStateException(
                    "Spring AI MCP client is not available. Set MCP_CLIENT_ENABLED=true before using MCP tool mode.");
        });
        for (ToolCallback callback : mcpToolCallbackProvider.getToolCallbacks()) {
            if (toolName.equals(callback.getToolDefinition().name())) {
                return callback;
            }
        }
        throw new IllegalStateException("MCP tool is not available: " + toolName);
    }
}
