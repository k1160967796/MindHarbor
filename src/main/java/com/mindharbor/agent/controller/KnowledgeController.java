package com.mindharbor.agent.controller;

import com.mindharbor.agent.dto.KnowledgeIngestRequest;
import com.mindharbor.agent.dto.KnowledgeIngestResponse;
import com.mindharbor.agent.service.knowledge.KnowledgeFileService;
import com.mindharbor.agent.service.knowledge.KnowledgeService;
import jakarta.validation.Valid;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import org.springframework.core.io.Resource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.PathVariable;
import com.mindharbor.agent.domain.KnowledgeChunk;
import com.mindharbor.agent.service.knowledge.KnowledgeImageStore;
import java.nio.file.Files;
import java.util.List;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@RestController
@RequestMapping("/api/admin/knowledge")
/**
 * 管理员知识库维护接口。
 *
 * <p>支持直接写入文本，也支持上传 PDF、Markdown、txt 文件作为 RAG 知识库来源。</p>
 */
public class KnowledgeController {

    private final KnowledgeService knowledgeService;
    private final KnowledgeFileService knowledgeFileService;
    private final KnowledgeImageStore imageStore;

    public KnowledgeController(KnowledgeService knowledgeService, KnowledgeFileService knowledgeFileService,
                               KnowledgeImageStore imageStore) {
        this.knowledgeService = knowledgeService;
        this.knowledgeFileService = knowledgeFileService;
        this.imageStore = imageStore;
    }

    @PostMapping
    public Mono<KnowledgeIngestResponse> ingest(@Valid @RequestBody KnowledgeIngestRequest request) {
        // JSON 接口适合脚本或调试时直接写入一段知识。
        return Mono.fromCallable(() -> new KnowledgeIngestResponse(request.source(),
                knowledgeService.ingest(request.source(), request.content()))).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping(value = "/file", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<KnowledgeIngestResponse> ingestFile(@RequestPart("file") FilePart file) {
        // WebFlux 的 FilePart 是流式数据，这里合并成 byte[] 后交给文件解析服务。
        return DataBufferUtils.join(file.content(), KnowledgeFileService.MAX_FILE_BYTES)
                .map(this::readBytes)
                .flatMap(bytes -> Mono.fromCallable(() -> {
                    int chunks = knowledgeFileService.ingest(file.filename(), bytes);
                    return new KnowledgeIngestResponse(file.filename(), chunks);
                }).subscribeOn(Schedulers.boundedElastic()))
                .onErrorMap(DataBufferLimitException.class, error ->
                        new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "文件不能超过 10MB"));
    }

    @GetMapping("/review")
    public Mono<List<ReviewChunk>> pendingReview() {
        return Mono.fromCallable(() -> knowledgeService.pendingReview().stream().map(ReviewChunk::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/review/{id}")
    public Mono<ResponseEntity<Void>> approve(@PathVariable Long id, @Valid @RequestBody ReviewRequest request) {
        return Mono.fromCallable(() -> {
            knowledgeService.approve(id, request.content());
            return ResponseEntity.noContent().<Void>build();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping(value = "/images/{reference}", produces = MediaType.IMAGE_PNG_VALUE)
    public Mono<ResponseEntity<Resource>> image(@PathVariable String reference) {
        return Mono.fromCallable(() -> {
            var path = imageStore.resolve(reference);
            if (!Files.isRegularFile(path)) throw new ResponseStatusException(HttpStatus.NOT_FOUND);
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                    .header("Cache-Control", "private, no-store")
                    .body((Resource) new FileSystemResource(path));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public record ReviewRequest(@NotBlank @Size(max = 12000) String content) { }
    public record ReviewChunk(Long id, String source, Integer pageNumber, String blockType,
                              String content, String imageRef) {
        static ReviewChunk from(KnowledgeChunk chunk) {
            return new ReviewChunk(chunk.getId(), chunk.getSource(), chunk.getPageNumber(),
                    chunk.getBlockType(), chunk.getContent(), chunk.getImageRef());
        }
    }

    private byte[] readBytes(DataBuffer dataBuffer) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(dataBuffer.readableByteCount());
            dataBuffer.asInputStream().transferTo(output);
            return output.toByteArray();
        } catch (IOException exception) {
            throw new IllegalArgumentException("文件读取失败：" + exception.getMessage());
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
