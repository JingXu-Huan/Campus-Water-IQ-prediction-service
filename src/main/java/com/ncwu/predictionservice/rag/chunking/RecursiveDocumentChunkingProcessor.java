package com.ncwu.predictionservice.rag.chunking;

import dev.langchain4j.data.document.Document;

import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 纯文本文档没有可稳定利用的 Markdown 标题语义，保留全文后交给递归切分器处理。
 */
public class RecursiveDocumentChunkingProcessor implements DocumentChunkingProcessor {

    private static final Set<String> SUPPORTED_EXTENSIONS = Set.of(".txt");

    @Override
    public boolean supports(String fileName) {
        String lowerCaseFileName = fileName.toLowerCase(Locale.ROOT);
        return SUPPORTED_EXTENSIONS.stream().anyMatch(lowerCaseFileName::endsWith);
    }

    @Override
    public List<Document> prepare(Document document) {
        return List.of(document);
    }

    @Override
    public String strategyName() {
        return "递归长度切分";
    }
}
