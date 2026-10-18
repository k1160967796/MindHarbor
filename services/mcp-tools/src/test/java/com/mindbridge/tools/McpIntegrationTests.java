package com.mindbridge.tools;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.*;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpIntegrationTests {
    static final String TOKEN="integration-only-token-with-at-least-32-characters";
    static final ObjectMapper JSON=new ObjectMapper();
    static final AtomicInteger POSTS=new AtomicInteger();
    static final List<String> KEYS=new ArrayList<>();
    static final Path DIR;
    static final HttpServer PROVIDER;
    static {
        try {
            DIR=Files.createTempDirectory("mindbridge-mcp-test");
            PROVIDER=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            PROVIDER.createContext("/emails",x->{
                boolean post=x.getRequestMethod().equals("POST");
                int count=POSTS.get();
                if(post){count=POSTS.incrementAndGet();KEYS.add(x.getRequestHeaders().getFirst("Idempotency-Key"));}
                String body=post?"{\"id\":\"email-"+count+"\"}":"{\"last_event\":\"delivered\"}";
                byte[] bytes=body.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                x.sendResponseHeaders(200,bytes.length);x.getResponseBody().write(bytes);x.close();
            });PROVIDER.start();
        }catch(Exception ex){throw new ExceptionInInitializerError(ex);}
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
        r.add("tools.token",()->TOKEN);r.add("tools.resend-key",()->"fake-provider-key");
        r.add("tools.resend-base",()->"http://127.0.0.1:"+PROVIDER.getAddress().getPort());
        r.add("tools.allowed-from",()->"test@example.com");r.add("tools.allowed-recipients",()->"teacher@example.com");
        r.add("tools.excel-path",()->DIR.resolve("reports.xlsx").toString());
        r.add("spring.datasource.url",()->"jdbc:h2:mem:mcp-integration;DB_CLOSE_DELAY=-1");
    }
    @LocalServerPort int port;
    @AfterAll static void stop(){PROVIDER.stop(0);}
    @Test void authenticatesRealMcpSessionAndExecutesIdempotentTools() throws Exception {
        var unauthorized=HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:"+port+"/sse")).GET().build(),HttpResponse.BodyHandlers.ofString());
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        var transport=WebFluxSseClientTransport.builder(WebClient.builder().baseUrl("http://localhost:"+port).defaultHeader("Authorization","Bearer "+TOKEN)).build();
        try(var client=McpClient.sync(transport).requestTimeout(Duration.ofSeconds(10)).build()) {
            client.initialize();
            assertThat(client.listTools().tools()).hasSize(4);
            Map<String,Object> report=new LinkedHashMap<>();
            report.put("reportId",500);report.put("userId",1);report.put("username","synthetic");report.put("sessionId","test-session");
            report.put("intent","CONSULT");report.put("emotion","ANXIETY");report.put("emotionScore",0.8);report.put("riskLevel","HIGH");
            report.put("confidence",0.9);report.put("summary","Integration test");report.put("content","No student data");report.put("createdAt",Instant.now().toString());
            assertThat(call(client,"mindbridge_write_excel_report",report).path("status").asText()).isEqualTo("WRITTEN");
            assertThat(call(client,"mindbridge_write_excel_report",report).path("status").asText()).isEqualTo("ALREADY_EXISTS");
            try(var wb=WorkbookFactory.create(DIR.resolve("reports.xlsx").toFile())){assertThat(wb.getSheetAt(0).getPhysicalNumberOfRows()).isEqualTo(2);}
            String key="mindbridge-mail-"+UUID.randomUUID();
            Map<String,Object> body=new LinkedHashMap<>(Map.of("from","test@example.com","to",List.of("teacher@example.com"),"subject","test","text","No student data","tags",List.of(Map.of("name","mail_key","value",key))));
            Map<String,Object> task=new LinkedHashMap<>(Map.of("taskId",101,"reportId",500,"alertId",50,"reminderRound",0,"idempotencyKey",key,"firstSendAt",Instant.now().toString(),"payloadJson",JSON.writeValueAsString(body)));
            JsonNode first=call(client,"mindbridge_send_risk_alert",task);
            assertThat(first.path("status").asText()).isEqualTo("ACCEPTED");
            assertThat(call(client,"mindbridge_send_risk_alert",task)).isEqualTo(first);
            assertThat(POSTS.get()).isEqualTo(1);assertThat(KEYS).containsExactly(key);
            assertThat(call(client,"mindbridge_get_mail_task",Map.of("idempotencyKey",key))).isEqualTo(first);
            assertThat(call(client,"mindbridge_get_mail_status",Map.of("providerEmailId",first.path("providerEmailId").asText())).path("deliveryStatus").asText()).isEqualTo("delivered");
            body.put("text","modified");task.put("payloadJson",JSON.writeValueAsString(body));
            assertThat(call(client,"mindbridge_send_risk_alert",task).path("status").asText()).isEqualTo("FAILED");
            task.put("taskId",102);task.put("idempotencyKey","mindbridge-mail-"+UUID.randomUUID());
            assertThat(call(client,"mindbridge_send_risk_alert",task).path("status").asText()).isEqualTo("FAILED");
            assertThat(POSTS.get()).isEqualTo(1);
        }
    }
    static JsonNode call(McpSyncClient c,String name,Map<String,Object> args)throws Exception {
        var result=c.callTool(new McpSchema.CallToolRequest(name,args));
        assertThat(result.isError()).isNotEqualTo(true);
        return JSON.readTree(((McpSchema.TextContent)result.content().get(0)).text());
    }
}
