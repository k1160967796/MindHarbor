package com.mindharbor.agent.service.ai;

import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.service.knowledge.OpenAiEmbeddingClient;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;

class LocalEmbeddingWarmupTests {
    HttpServer server;OpenAiEmbeddingClient client;AtomicInteger calls=new AtomicInteger();
    @AfterEach void close(){if(client!=null)client.close();if(server!=null)server.stop(0);}
    MindHarborProperties setup(boolean sameOrigin) throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/embeddings",exchange->{
            exchange.getRequestBody().readAllBytes();int count=calls.incrementAndGet();
            if(count==1)try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();}
            byte[] body="{\"data\":[{\"embedding\":[0.1,0.2]}]}".getBytes(StandardCharsets.UTF_8);
            try{exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);exchange.getResponseBody().write(body);}
            finally{exchange.close();}
        });server.start();
        var p=new MindHarborProperties();String url="http://127.0.0.1:"+server.getAddress().getPort();
        p.getEmbedding().setBaseUrl(url);p.getEmbedding().setApiKey("fixture");p.getAi().setProvider("ollama");
        p.getAi().getOllama().setBaseUrl(sameOrigin?url:"http://127.0.0.1:1");return p;
    }
    @Test void startupWarmupIsAsyncAndSingleFlight() throws Exception {
        client=new OpenAiEmbeddingClient(setup(true),WebClient.builder());
        long start=System.nanoTime();client.warmLocalModel();client.warmLocalModel();
        assertThat(Duration.ofNanos(System.nanoTime()-start)).isLessThan(Duration.ofMillis(200));
        awaitCalls(1);Thread.sleep(350);client.warmLocalModel();assertThat(calls.get()).isEqualTo(1);
    }
    @Test void remoteEmbeddingEndpointIsNeverWarmed() throws Exception {
        client=new OpenAiEmbeddingClient(setup(false),WebClient.builder());client.warmLocalModel();Thread.sleep(100);
        assertThat(calls.get()).isZero();
    }
    @Test void foregroundTimeoutCanFinishColdLoadInIndependentWarmup() throws Exception {
        client=new OpenAiEmbeddingClient(setup(true),WebClient.builder());
        assertThatThrownBy(()->client.embed("test",Duration.ofMillis(80))).isInstanceOf(RuntimeException.class);
        awaitCalls(2);
        assertThat(client.embed("test",Duration.ofSeconds(2))).containsExactly(.1,.2);
        assertThat(calls.get()).isEqualTo(3);
    }
    void awaitCalls(int n) throws Exception {long end=System.nanoTime()+Duration.ofSeconds(4).toNanos();
        while(calls.get()<n&&System.nanoTime()<end)Thread.sleep(10);assertThat(calls.get()).isGreaterThanOrEqualTo(n);}
}
