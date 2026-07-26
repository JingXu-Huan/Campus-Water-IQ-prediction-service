package com.ncwu.predictionservice.rag.chunking;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Markdown 的结构化预处理器：先依据标题层级组织章节，再交给递归切分器处理超长章节。
 */
public class MarkdownDocumentChunkingProcessor implements DocumentChunkingProcessor {

    public static final String HEADING_PATH_METADATA_KEY = "rag_heading_path";
    private static final String PREAMBLE_HEADING = "文档前言";
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)(?:\\s+#+)?\\s*$");

    @Override
    public boolean supports(String fileName) {
        return fileName.toLowerCase().endsWith(".md");
    }

    @Override
    public List<Document> prepare(Document document) {
        List<Document> sections = new ArrayList<>();
        // 下标 0 至 5 分别缓存 # 至 ###### 的最近一次标题，用于构造章节的完整上下文。
        String[] headingStack = new String[6];
        // 没有一级标题前的文本仍应被索引，统一归入“文档前言”章节。
        String headingPath = PREAMBLE_HEADING;
        StringBuilder sectionContent = new StringBuilder();
        boolean insideFencedCodeBlock = false;

        for (String line : document.text().split("\\R", -1)) {
            String trimmedLine = line.trim();
            // 围栏代码块中可能出现 Markdown 示例；此处仅切换状态，不解析其中的标题。
            if (trimmedLine.startsWith("```") || trimmedLine.startsWith("~~~")) {
                insideFencedCodeBlock = !insideFencedCodeBlock;
            }

            Matcher matcher = insideFencedCodeBlock ? null : MARKDOWN_HEADING.matcher(line);
            if (matcher != null && matcher.matches()) {
                // 新标题意味着上一个章节结束，先使用它已有的标题路径落库。
                addSection(sections, sectionContent, document.metadata(), headingPath);

                int level = matcher.group(1).length();
                headingStack[level - 1] = matcher.group(2).trim();
                // 进入较高层级时，之前的子标题已不再属于当前章节，需要清空。
                Arrays.fill(headingStack, level, headingStack.length, null);
                headingPath = Arrays.stream(headingStack)
                        .filter(title -> title != null && !title.isBlank())
                        .collect(Collectors.joining(" > "));
            }
            sectionContent.append(line).append(System.lineSeparator());
        }
        // 最后一个章节后没有下一个标题触发保存，循环结束时需要主动落入结果集。
        addSection(sections, sectionContent, document.metadata(), headingPath);
        return sections;
    }

    @Override
    public String strategyName() {
        return "Markdown 标题层级切分";
    }

    private void addSection(List<Document> sections, StringBuilder sectionContent,
                            Metadata documentMetadata, String headingPath) {
        if (sectionContent.isEmpty() || sectionContent.toString().isBlank()) {
            sectionContent.setLength(0);
            return;
        }
        // 复制原始元数据，确保来源、文件标识和内容指纹不会在章节切片时丢失。
        Metadata metadata = documentMetadata.copy().put(HEADING_PATH_METADATA_KEY, headingPath);
        sections.add(Document.from(sectionContent.toString().strip(), metadata));
        sectionContent.setLength(0);
    }
}
