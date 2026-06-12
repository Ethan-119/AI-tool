package com.voicedraw.model;

import java.util.Map;

/**
 * LLM 输出的语义操作描述，尚未映射为像素值。
 */
public record SemanticOp(
    String shape,               // circle | rect | line | triangle | ellipse | undo | clear | modify
    String x, String y,         // 语义坐标: left | center | right, top | middle | bottom
    String size,                // small | medium | large
    String color,               // 颜色名
    String fillColor,
    String strokeColor,
    boolean fill,
    boolean stroke,
    String text,
    Map<String, Object> extra,
    Integer targetIndex         // modify 类型专用：修改第几个元素，null 表示新增
) {}
