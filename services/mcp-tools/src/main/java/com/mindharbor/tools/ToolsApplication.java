package com.mindharbor.tools;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.WebFilter;

@SpringBootApplication
public class ToolsApplication {
    public static void main(String[] args) { SpringApplication.run(ToolsApplication.class,args); }
    @Bean ToolCallbackProvider tools(ToolMethods methods) {
        return MethodToolCallbackProvider.builder().toolObjects(methods).build();
    }
    @Bean WebFilter serviceAuthentication(@Value("${tools.token}") String token) {
        if(token.length()<32) throw new IllegalStateException("Configure a strong MCP_SERVICE_TOKEN before starting tools");
        byte[] expected=("Bearer "+token).getBytes(StandardCharsets.UTF_8);
        return (exchange,chain)->{
            String path=exchange.getRequest().getPath().value();
            if(path.equals("/actuator/health")) return chain.filter(exchange);
            String supplied=exchange.getRequest().getHeaders().getFirst("Authorization");
            if(supplied==null || !MessageDigest.isEqual(expected,supplied.getBytes(StandardCharsets.UTF_8))) {
                exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                return exchange.getResponse().setComplete();
            }
            return chain.filter(exchange);
        };
    }
}
