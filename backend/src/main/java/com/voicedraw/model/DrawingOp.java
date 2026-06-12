package com.voicedraw.model;

/**
 * 策略执行后的精确绘图指令，已映射为像素值。
 */
public record DrawingOp(
    String type,        // circle | rect | line | triangle | ellipse | text | image | undo | clear | modify
    double x, double y,
    double width,
    double height,
    String color,
    String fillColor,
    String strokeColor,
    double lineWidth,
    String text,
    String imageUrl,
    int targetIndex     // modify 类型专用，-1 表示不适用
) {}
