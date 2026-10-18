package com.mindharbor.agent.config;

import com.mindharbor.agent.service.mcp.AlertNotifier;
import com.mindharbor.agent.service.mcp.ExcelReportWriter;
import com.mindharbor.agent.service.mcp.HttpAlertNotifier;
import com.mindharbor.agent.service.mcp.HttpExcelReportWriter;
import com.mindharbor.agent.service.mcp.LocalExcelReportWriter;
import com.mindharbor.agent.service.mcp.LogAlertNotifier;
import com.mindharbor.agent.service.mcp.McpAlertNotifier;
import com.mindharbor.agent.service.mcp.McpExcelReportWriter;
import com.mindharbor.agent.service.mcp.McpToolClient;
import com.mindharbor.agent.service.mcp.MindHarborMcpTools;
import com.mindharbor.agent.service.mcp.SmtpAlertNotifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskExecutor;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.reactive.function.client.WebClient;

@Configuration
/**
 * MCP 工具链配置。
 *
 * <p>这里按配置选择 Excel 写入方式和预警通知方式，同时提供独立线程池，
 * 避免后台工具调用阻塞学生端聊天。</p>
 */
public class McpToolConfig {

    @Bean
    public TaskExecutor mcpTaskExecutor() {//MCP工具链线程池
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setThreadNamePrefix("mindharbor-mcp-");
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(6);
        executor.setQueueCapacity(200);
        executor.initialize();
        return executor;
    }

    @Bean
    public ToolCallbackProvider mindHarborMcpToolProvider(MindHarborMcpTools mindHarborMcpTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(mindHarborMcpTools)
                .build();
    }

    @Bean
    public McpToolClient mcpToolClient(
            ObjectProvider<SyncMcpToolCallbackProvider> toolCallbackProvider,
            ObjectMapper objectMapper,
            org.springframework.core.env.Environment environment
    ) {
        McpToolClient client = new McpToolClient(toolCallbackProvider, objectMapper);
        if (environment.getProperty("MCP_REMOTE_ENABLED", Boolean.class, false))
            client.remote(environment.getRequiredProperty("MCP_REMOTE_URL"), environment.getRequiredProperty("MCP_SERVICE_TOKEN"));
        return client;
    }

    @Bean
    public ExcelReportWriter excelReportWriter(
            MindHarborProperties properties,
            WebClient.Builder webClientBuilder,
            McpToolClient mcpToolClient
    ) {
        String mode = properties.getMcp().getExcel().getMode();
        if ("mcp".equalsIgnoreCase(mode)) {
            return new McpExcelReportWriter(mcpToolClient);
        }
        if ("http".equalsIgnoreCase(mode)) {
            return new HttpExcelReportWriter(webClientBuilder, properties);
        }
        return new LocalExcelReportWriter(properties);
    }

    @Bean
    public AlertNotifier alertNotifier(
            MindHarborProperties properties,
            WebClient.Builder webClientBuilder,
            JavaMailSender mailSender,
            McpToolClient mcpToolClient
    ) {
        String mode = properties.getMcp().getEmail().getMode();
        if ("mcp".equalsIgnoreCase(mode)) {
            return new McpAlertNotifier(mcpToolClient);
        }
        if ("http".equalsIgnoreCase(mode)) {
            return new HttpAlertNotifier(webClientBuilder, properties);
        }
        if ("smtp".equalsIgnoreCase(mode)) {
            return new SmtpAlertNotifier(mailSender, properties);
        }
        return new LogAlertNotifier();
    }
}
