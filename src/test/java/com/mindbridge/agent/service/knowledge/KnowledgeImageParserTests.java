package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.*;
import com.mindbridge.agent.config.KnowledgeMediaProperties;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.reactive.function.client.WebClient;

class KnowledgeImageParserTests {
    @TempDir Path directory;
    HttpServer server;
    KnowledgeMediaProperties properties;
    KnowledgeImageStore images;
    KnowledgeImageParser parser;
    AtomicBoolean visionFails = new AtomicBoolean();
    AtomicBoolean sawImage = new AtomicBoolean();

    @BeforeEach void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/parse", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = """
                    {"blocks":[{"type":"table","text":"|项目|说明|\\n|睡眠|记录作息|"},
                    {"type":"chart","text":"预约流程","bbox":[0,0,40,40]}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/api/chat", exchange -> {
            String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            sawImage.set(request.contains("images") && request.contains("iVBOR"));
            byte[] body = "{\"message\":{\"content\":\"选择时间 → 提交预约\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(visionFails.get() ? 503 : 200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        properties = new KnowledgeMediaProperties();
        properties.setStoragePath(directory.toString());
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        properties.setOcrBaseUrl(base);
        properties.setVisionBaseUrl(base);
        images = new KnowledgeImageStore(properties);
        parser = new KnowledgeImageParser(properties, images, WebClient.builder());
    }

    @AfterEach void tearDown() { server.stop(0); }

    @Test void parsesLayoutAndCallsVisionWithRealImageBytes() throws Exception {
        var result = parser.parse(new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB), 3);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("table");
        assertThat(result.get(1).text()).contains("提交预约");
        assertThat(result).allSatisfy(block -> {
            assertThat(block.pageNumber()).isEqualTo(3);
            assertThat(block.needsReview()).isTrue();
            assertThat(Files.exists(images.resolve(block.imageRef()))).isTrue();
        });
        assertThat(sawImage).isTrue();
    }

    @Test void failedVisionPreservesOcrAndOriginalForReview() throws Exception {
        visionFails.set(true);
        var result = parser.parse(new BufferedImage(80, 80, BufferedImage.TYPE_INT_RGB), 1);
        assertThat(result.get(1).text()).contains("预约流程", "视觉识别失败");
        assertThat(result.get(1).needsReview()).isTrue();
    }

    @Test void rejectsUnsafeReferencesAndOversizedDecodedImages() throws Exception {
        assertThatThrownBy(() -> images.resolve("../../application.yml")).isInstanceOf(IllegalArgumentException.class);
        properties.setMaxImagePixels(10);
        byte[] png = images.png(new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB));
        assertThatThrownBy(() -> images.decode(png)).isInstanceOf(IllegalArgumentException.class);
    }
}
