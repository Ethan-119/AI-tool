package com.voicedraw.model;

/**
 * 画布元素状态，用于 Redis 结构化存储和多维度匹配。
 */
public record ElementState(
    int index,
    String type,       // circle, rect, line, triangle, ellipse, image, text
    double x, double y,
    double width, double height,
    String color,      // hex, e.g. "#FF0000"
    String fillColor,  // hex or null
    String strokeColor,// hex or null
    String position,   // 九宫格: 左上, 中上, 右上, 左间, 中间, 右间, 左下, 中下, 右下
    String size,       // 大, 中, 小
    String colorName   // 红, 蓝, 绿, ...
) {}
