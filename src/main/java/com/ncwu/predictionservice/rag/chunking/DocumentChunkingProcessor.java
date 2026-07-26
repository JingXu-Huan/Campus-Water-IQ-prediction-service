package com.ncwu.predictionservice.rag.chunking;

import dev.langchain4j.data.document.Document;

import java.util.List;

/**
 * 负责将某一类知识文件预处理为可供嵌入模型切分的文档单元。
 *
 * <p>预处理后的文档仍会经过 LangChain4j 的递归切分器，以保证超长内容不会超过向量片段大小。</p>
 */
public interface DocumentChunkingProcessor {

    /** 判断当前处理器是否支持该文件名。 */
    boolean supports(String fileName);

    /** 按文件类型保留结构信息，并返回待二次切分的文档单元。 */
    List<Document> prepare(Document document);

    /** 用于日志，说明本次索引采用的处理策略。 */
    String strategyName();
}
