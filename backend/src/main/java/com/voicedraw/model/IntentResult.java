package com.voicedraw.model;

import java.util.List;

/**
 * LLM 意图解析结果。
 * LLM 只输出语义值（如 "center", "large"），由策略层映射为像素。
 */
public record IntentResult(
    boolean understood,
    String clarification,
    String type,               // 意图类型: geometry | image | template
    List<SemanticOp> operations
) {}
