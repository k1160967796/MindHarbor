package com.mindharbor.agent.service.knowledge;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import com.mindharbor.agent.config.KnowledgeMediaProperties;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

@Service
/**
 * 管理员文件上传知识库服务。
 *
 * <p>负责文件大小校验、类型识别和文本抽取，抽取后的文本交给 KnowledgeService 处理。</p>
 */
public class KnowledgeFileService {

    public static final int MAX_FILE_BYTES = 10 * 1024 * 1024;

    private final KnowledgeService knowledgeService;
    private final KnowledgeMediaProperties media;
    private final KnowledgeImageParser imageParser;
    private final KnowledgeImageStore images;
    private final java.util.concurrent.Semaphore uploadSlot = new java.util.concurrent.Semaphore(1);

    public KnowledgeFileService(KnowledgeService knowledgeService, KnowledgeMediaProperties media,
                                KnowledgeImageParser imageParser, KnowledgeImageStore images) {
        this.knowledgeService = knowledgeService;
        this.media = media;
        this.imageParser = imageParser;
        this.images = images;
    }

    public int ingest(String filename, byte[] bytes) {
        if (!uploadSlot.tryAcquire()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.TOO_MANY_REQUESTS, "已有文档正在解析，请稍后重试");
        }
        try { return ingestFile(filename, bytes); }
        finally { uploadSlot.release(); }
    }

    private int ingestFile(String filename, byte[] bytes) {
        // 文件上传入口只负责校验和抽取文本，真正切块、向量化、落库交给 KnowledgeService。
        if (bytes.length == 0) {
            throw new IllegalArgumentException("文件内容为空");
        }
        if (bytes.length > MAX_FILE_BYTES) {
            throw new IllegalArgumentException("文件不能超过 10MB");
        }
        String source = sanitizeSource(filename);
        // Parse every page before replacing the old source; a parser failure preserves old knowledge.
        return knowledgeService.ingestBlocks(source, extractBlocks(source, bytes));
    }

    private List<KnowledgeBlock> extractBlocks(String filename, byte[] bytes) {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".pdf")) {
            return extractPdf(bytes);
        }
        if (lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")) {
            if (!media.isEnabled()) throw new IllegalArgumentException("图片解析尚未启用，请先配置文档解析服务");
            try {
                return imageParser.parse(images.decode(bytes), 1);
            } catch (java.io.IOException exception) {
                throw new IllegalArgumentException("图片解析失败", exception);
            }
        }
        // Markdown 和 txt 都按 UTF-8 文本处理，适合管理员维护轻量知识库。
        if (lower.endsWith(".md") || lower.endsWith(".markdown") || lower.endsWith(".txt")) {
            return List.of(new KnowledgeBlock(null, "text", new String(bytes, StandardCharsets.UTF_8), null, false));
        }
        throw new IllegalArgumentException("仅支持 PDF、Markdown、txt、PNG 和 JPEG 文件");
    }

    private List<KnowledgeBlock> extractPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            if (document.getNumberOfPages() > media.getMaxPdfPages()) {
                throw new IllegalArgumentException("PDF 页数超出限制，请拆分后上传");
            }
            List<KnowledgeBlock> blocks = new ArrayList<>();
            PDFRenderer renderer = new PDFRenderer(document);
            renderer.setSubsamplingAllowed(true);
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 0; page < document.getNumberOfPages(); page++) {
                if (media.isEnabled()) {
                    int dpi = Math.max(72, Math.min(media.getRenderDpi(), 300));
                    var box = document.getPage(page).getCropBox();
                    images.checkPixels(Math.ceil(box.getWidth() * dpi / 72.0), Math.ceil(box.getHeight() * dpi / 72.0));
                    var image = renderer.renderImageWithDPI(page, dpi, ImageType.RGB);
                    try { blocks.addAll(imageParser.parse(image, page + 1)); }
                    finally { image.flush(); }
                } else {
                    stripper.setStartPage(page + 1);
                    stripper.setEndPage(page + 1);
                    String text = stripper.getText(document);
                    if (!text.isBlank()) blocks.add(new KnowledgeBlock(page + 1, "text", text, null, false));
                }
            }
            if (blocks.isEmpty()) throw new IllegalArgumentException("没有解析出可用文本；扫描 PDF 请启用图片解析");
            return blocks;
        } catch (java.io.IOException exception) {
            throw new IllegalArgumentException("PDF 文本解析失败：" + exception.getMessage());
        }
    }

    private String sanitizeSource(String filename) {
        String source = filename == null || filename.isBlank() ? "uploaded-knowledge" : filename.trim();
        // source 会进入数据库和后台列表，去掉路径分隔符避免显示本地路径。
        source = source.replaceAll("[\\\\/]+", "-");
        return source.length() > 180 ? source.substring(source.length() - 180) : source;
    }
}
