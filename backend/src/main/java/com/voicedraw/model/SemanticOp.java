package com.voicedraw.model;

import java.util.Map;

/**
 * LLM 输出的语义操作描述，尚未映射为像素值。
 * 例如 x="center", size="large", color="red"
 */
public record SemanticOp(
    String shape,               // 形状: circle | rect | line | triangle | ellipse
    String x, String y,         // 语义坐标: left | center | right, top | middle | bottom
    String size,                // small | medium | large，或具体数值 "100"
    String color,               // 颜色名 red | blue | green ...
    String fillColor,
    String strokeColor,
    boolean fill,
    boolean stroke,
    String text,                // 文本内容（备用）
    Map<String, Object> extra   // 模板参数等扩展字段
) {}
