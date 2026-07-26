package com.ncwu.predictionservice.rag;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** 统一提取知识库文件的纯文本内容，供不同的切分处理器使用。 */
public class KnowledgeDocumentContentReader {

    public String read(Resource resource, String fileName) throws IOException {
        String extension = extensionOf(fileName);
        return switch (extension) {
            case ".md", ".txt" -> resource.getContentAsString(StandardCharsets.UTF_8);
            case ".doc" -> readDoc(resource);
            case ".docx" -> readDocx(resource);
            default -> throw new IllegalArgumentException("RAG 不支持的知识文件类型：" + fileName);
        };
    }

    private String readDoc(Resource resource) throws IOException {
        try (InputStream inputStream = resource.getInputStream();
             HWPFDocument document = new HWPFDocument(inputStream);
             WordExtractor extractor = new WordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String readDocx(Resource resource) throws IOException {
        try (InputStream inputStream = resource.getInputStream();
             XWPFDocument document = new XWPFDocument(inputStream);
             XWPFWordExtractor extractor = new XWPFWordExtractor(document)) {
            return extractor.getText();
        }
    }

    private String extensionOf(String fileName) {
        int lastDot = fileName.lastIndexOf('.');
        return lastDot < 0 ? "" : fileName.substring(lastDot).toLowerCase(Locale.ROOT);
    }
}
