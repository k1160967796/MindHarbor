package com.mindharbor.agent.service.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mindharbor.agent.config.MindHarborProperties;
import com.mindharbor.agent.domain.*;
import com.mindharbor.agent.service.knowledge.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class MemoryFactPolicyTests {
    @Test void poorSleepIsNotReadAsGoodSleep() {
        var c=MemoryFactPolicy.explicitFacts("最近我睡得不太好",Instant.now(),new ObjectMapper()).get(0);
        assertThat(c.value()).isEqualTo("difficulty");assertThat(c.status()).isEqualTo(MemoryFactStatus.ACTIVE);
    }
    @ParameterizedTest @ValueSource(strings={"以后请不要简短回答","我不希望以后用中文回答"})
    void negatedPreferenceIsNotPromoted(String text) {
        assertThat(MemoryFactPolicy.explicitFacts(text,Instant.now(),new ObjectMapper()))
                .isNotEmpty().allSatisfy(c->assertThat(c.status()).isEqualTo(MemoryFactStatus.NEEDS_CONFIRMATION));
    }
    @ParameterizedTest @ValueSource(strings={"最近我的睡眠没有恢复","我现在不能睡着","不是说我现在睡得好","我不确定是不是已经恢复睡眠"})
    void ambiguousNegationCannotBecomeActiveImprovement(String text) {
        var n=new ObjectMapper().valueToTree(Map.of("type","WELLBEING_PATTERN","key","sleep_status","value","improved","scope","GLOBAL",
                "subject","SELF","temporal","CURRENT","summary","用户睡眠已经改善","evidence",text,"confidence",.99));
        assertThat(MemoryFactPolicy.parse(n,text,Instant.now()).status()).isEqualTo(MemoryFactStatus.NEEDS_CONFIRMATION);
    }
    @Test void absentAbsoluteDateIsNotAcceptedAsFactTime() {
        String text="最近晚上睡不着";
        var n=new ObjectMapper().valueToTree(Map.of("type","WELLBEING_PATTERN","key","sleep_status","value","difficulty","scope","GLOBAL",
                "subject","SELF","temporal","CURRENT","summary","用户存在睡眠困难","evidence",text,"confidence",.95,"effectiveDate","2020-01-01"));
        var c=MemoryFactPolicy.parse(n,text,Instant.now());assertThat(c.effectiveAt()).isNull();assertThat(c.status()).isEqualTo(MemoryFactStatus.NEEDS_CONFIRMATION);
    }
    @Test void unknownAttributeRequiresConfirmation() {
        String text="我最近总是反复观察天空";
        var n=new ObjectMapper().valueToTree(Map.of("type","PERSONAL_CONTEXT","key","unclassified_observation","value","yes","scope","GLOBAL",
                "subject","SELF","temporal","CURRENT","summary",text,"evidence",text,"confidence",.95));
        assertThat(MemoryFactPolicy.parse(n,text,Instant.now()).status()).isEqualTo(MemoryFactStatus.NEEDS_CONFIRMATION);
    }
    @Test void indexCleanupOnlyRemovesOlderVersions() throws Exception {
        try(var server=new FakeChroma()) {
        var props=new MindHarborProperties();props.getMemory().setUseChroma(true);
        props.getMemory().setChromaBaseUrl(server.url());
        var embedding=mock(EmbeddingClient.class);when(embedding.embed(anyString())).thenReturn(List.of(.1,.2));
        var gateway=new UserMemoryChromaGateway(props,WebClient.builder(),embedding);
        var item=new UserMemoryItem();ReflectionTestUtils.setField(item,"id",7L);
        var user=new UserAccount();ReflectionTestUtils.setField(user,"id",1L);item.setUser(user);
        item.setFactKey("language");item.setFactScope("GLOBAL");item.setFactStatus(MemoryFactStatus.ACTIVE);
        item.setSummary("希望使用中文回答");item.setSourceOrder(9);
        gateway.mirror(item);
        assertThat(server.requests.get("upsert").path("ids").get(0).asText()).isEqualTo("memory:7-r9");
        var filter=server.requests.get("delete").path("where").path("$and").get(1).path("sourceOrder");
        assertThat(filter.path("$lt").asLong()).isEqualTo(9);assertThat(filter.has("$lte")).isFalse();
        }
    }
    @Test void expiredMemoryIsRemovedWithoutEmbeddingNewDocument() throws Exception {
        try(var server=new FakeChroma()) {
        var props=new MindHarborProperties();props.getMemory().setUseChroma(true);
        props.getMemory().setChromaBaseUrl(server.url());var embedding=mock(EmbeddingClient.class);
        var gateway=new UserMemoryChromaGateway(props,WebClient.builder(),embedding);
        var item=new UserMemoryItem();ReflectionTestUtils.setField(item,"id",7L);item.setFactStatus(MemoryFactStatus.ACTIVE);
        item.setExpiresAt(Instant.EPOCH);item.setSourceOrder(9);gateway.mirror(item);
        verifyNoInteractions(embedding);assertThat(server.requests).doesNotContainKey("upsert");
        assertThat(server.requests.get("delete").path("where").path("$and").get(1).path("sourceOrder").path("$lte").asLong()).isEqualTo(9);
        }
    }
    static class FakeChroma implements AutoCloseable {
        final com.sun.net.httpserver.HttpServer server;
        final Map<String,com.fasterxml.jackson.databind.JsonNode> requests=new java.util.concurrent.ConcurrentHashMap<>();
        FakeChroma() throws java.io.IOException {
            server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/",exchange->{
                String path=exchange.getRequestURI().getPath(),operation=path.substring(path.lastIndexOf('/')+1);
                requests.put(operation,new ObjectMapper().readTree(exchange.getRequestBody()));
                byte[] result=(operation.equals("collections")?"{\"id\":\"fixture\"}":"{}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type","application/json");exchange.sendResponseHeaders(200,result.length);
                exchange.getResponseBody().write(result);exchange.close();
            });server.start();
        }
        String url(){return "http://127.0.0.1:"+server.getAddress().getPort();}
        public void close(){server.stop(0);}
    }
}
