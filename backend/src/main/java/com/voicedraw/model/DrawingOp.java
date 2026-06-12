package com.voicedraw.model;

/**
 * 策略执行后的精确绘图指令，已映射为像素值。
 * 可以直接序列化为 JSON 推送给前端 Canvas。
 */
public record DrawingOp(
    String type,        // 形状类型: circle | rect | line | triangle | ellipse | text | image
    double x, double y,
    double width,       // 矩形/椭圆为宽度，圆为半径
    double height,      // 矩形/椭圆为高度，圆等同 width
    String color,
    String fillColor,
    String strokeColor,
    double lineWidth,
    String text,
    String imageUrl     // image 类型专用
) {}
