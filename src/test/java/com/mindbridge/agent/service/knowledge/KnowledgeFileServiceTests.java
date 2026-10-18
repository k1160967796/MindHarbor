package com.mindbridge.agent.service.knowledge;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.mindbridge.agent.config.KnowledgeMediaProperties;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

class KnowledgeFileServiceTests {
    @TempDir Path directory;
    KnowledgeService service = mock(KnowledgeService.class);
    KnowledgeImageParser parser = mock(KnowledgeImageParser.class);
    KnowledgeMediaProperties properties = new KnowledgeMediaProperties();
    KnowledgeFileService files;

    @BeforeEach void setUp() {
        properties.setStoragePath(directory.toString());
        files = new KnowledgeFileService(service, properties, parser, new KnowledgeImageStore(properties));
    }

    @Test void nativePdfRetainsPageNumbersWithoutCallingOcr() throws Exception {
        files.ingest("notes.pdf", pdf(true));
        ArgumentCaptor<List<KnowledgeBlock>> blocks = ArgumentCaptor.forClass(List.class);
        verify(service).ingestBlocks(eq("notes.pdf"), blocks.capture());
        assertThat(blocks.getValue()).extracting(KnowledgeBlock::pageNumber).containsExactly(1, 2);
        assertThat(blocks.getValue().get(0).text()).contains("Page 1");
        verifyNoInteractions(parser);
    }

    @Test void parsesScanPagesAndDoesNotIngestIfLaterPageFails() throws Exception {
        properties.setEnabled(true);
        when(parser.parse(any(), eq(1))).thenReturn(List.of(new KnowledgeBlock(1, "table", "data", "ref", true)));
        when(parser.parse(any(), eq(2))).thenThrow(new IllegalStateException("OCR unavailable"));
        assertThatThrownBy(() -> files.ingest("scan.pdf", pdf(false))).hasMessage("OCR unavailable");
        verifyNoInteractions(service);
    }

    @Test void textUploadIsUtf8AndRejectsUnsupportedFiles() {
        files.ingest("notes.md", "# 睡眠\n保持作息".getBytes(StandardCharsets.UTF_8));
        verify(service).ingestBlocks(eq("notes.md"), argThat(blocks -> blocks.get(0).text().contains("睡眠")));
        assertThatThrownBy(() -> files.ingest("a.exe", new byte[]{1})).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> files.ingest("a.png", new byte[]{1})).hasMessageContaining("尚未启用");
    }

    @Test void rejectsExcessPagesBeforeReplacingSource() throws Exception {
        properties.setMaxPdfPages(1);
        assertThatThrownBy(() -> files.ingest("notes.pdf", pdf(true))).hasMessageContaining("页数");
        verifyNoInteractions(service, parser);
    }

    private byte[] pdf(boolean text) throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int i = 1; i <= 2; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                if (text) try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
                    stream.beginText();
                    stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    stream.newLineAtOffset(40, 700);
                    stream.showText("Page " + i);
                    stream.endText();
                }
            }
            document.save(bytes);
            return bytes.toByteArray();
        }
    }
}
