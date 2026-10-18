package com.mindharbor.agent.service.knowledge;

import com.mindharbor.agent.config.KnowledgeMediaProperties;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

@Component
public class KnowledgeImageStore {
    private final KnowledgeMediaProperties properties;

    public KnowledgeImageStore(KnowledgeMediaProperties properties) { this.properties = properties; }

    public BufferedImage decode(byte[] bytes) throws IOException {
        // Inspect dimensions before allocating an image (compressed files can be very large).
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IllegalArgumentException("无法读取图片，请使用 PNG 或 JPEG");
            ImageReader reader = readers.next();
            try {
                reader.setInput(input);
                checkPixels(reader.getWidth(0), reader.getHeight(0));
                return reader.read(0);
            } finally { reader.dispose(); }
        }
    }

    public void checkPixels(double width, double height) {
        if (width <= 0 || height <= 0 || width * height > properties.getMaxImagePixels()) {
            throw new IllegalArgumentException("图片或 PDF 页面像素过大，请降低分辨率或拆分文档");
        }
    }

    public byte[] png(BufferedImage image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IOException("PNG 编码失败");
        return out.toByteArray();
    }

    public String save(byte[] png) throws IOException {
        try {
            String ref = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(png)) + ".png";
            Path path = resolve(ref);
            Files.createDirectories(path.getParent());
            try {
                Files.write(path, png, StandardOpenOption.CREATE_NEW);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // Identical content shares a reference. Never overwrite a reviewed original.
            }
            return ref;
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public Path resolve(String reference) {
        if (reference == null || !reference.matches("[a-f0-9]{64}\\.png")) {
            throw new IllegalArgumentException("无效的知识图片引用");
        }
        return Path.of(properties.getStoragePath()).toAbsolutePath().normalize().resolve(reference);
    }
}
