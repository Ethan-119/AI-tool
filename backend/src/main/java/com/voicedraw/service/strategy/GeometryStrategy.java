package com.voicedraw.service.strategy;

import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.SemanticOp;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 规则引擎：将 LLM 的语义坐标/尺寸映射为像素值。
 * 画布基准 800x600。
 */
@Component
public class GeometryStrategy implements IntentStrategy {

    // 画布尺寸
    static final double CANVAS_W = 800;
    static final double CANVAS_H = 600;

    // 颜色名 → hex（12 种基础色）
    static final Map<String, String> COLORS = Map.ofEntries(
        Map.entry("红", "#FF0000"), Map.entry("red", "#FF0000"),
        Map.entry("蓝", "#0000FF"), Map.entry("blue", "#0000FF"),
        Map.entry("绿", "#00FF00"), Map.entry("green", "#00FF00"),
        Map.entry("黄", "#FFFF00"), Map.entry("yellow", "#FFFF00"),
        Map.entry("黑", "#000000"), Map.entry("black", "#000000"),
        Map.entry("白", "#FFFFFF"), Map.entry("white", "#FFFFFF"),
        Map.entry("橙", "#FF8800"), Map.entry("orange", "#FF8800"),
        Map.entry("紫", "#8800FF"), Map.entry("purple", "#8800FF"),
        Map.entry("粉", "#FF88CC"), Map.entry("pink", "#FF88CC"),
        Map.entry("灰", "#888888"), Map.entry("gray", "#888888"),
        Map.entry("棕", "#884400"), Map.entry("brown", "#884400"),
        Map.entry("青", "#00FFFF"), Map.entry("cyan", "#00FFFF")
    );

    // 语义坐标映射（9 宫格）
    static double mapX(String pos) {
        return switch (pos) {
            case "left", "左"   -> CANVAS_W * 0.25;
            case "right", "右"  -> CANVAS_W * 0.75;
            default             -> CANVAS_W * 0.5;   // 默认居中
        };
    }
    static double mapY(String pos) {
        return switch (pos) {
            case "top", "上"    -> CANVAS_H * 0.25;
            case "bottom", "下" -> CANVAS_H * 0.75;
            default             -> CANVAS_H * 0.5;
        };
    }
    static double mapSize(String size) {
        return switch (size) {
            case "small", "小"  -> 60;
            case "large", "大"  -> 150;
            default             -> 100;   // 默认中等尺寸
        };
    }
    static String mapColor(String name) {
        if (name == null) return "#000000";
        return COLORS.getOrDefault(name.toLowerCase(), name);  // 如果已是 hex 直接透传
    }

    @Override
    public String type() { return "geometry"; }

    @Override
    public List<DrawingOp> execute(List<SemanticOp> ops) {
        List<DrawingOp> result = new ArrayList<>();
        for (SemanticOp op : ops) {
            String shape = op.shape() != null ? op.shape() : "circle";
            double x = mapX(op.x());
            double y = mapY(op.y());
            double size = mapSize(op.size());
            String c = mapColor(op.color());
            String fc = op.fill() ? mapColor(op.fillColor() != null ? op.fillColor() : op.color()) : null;
            String sc = op.stroke() ? mapColor(op.strokeColor() != null ? op.strokeColor() : op.color()) : c;

            result.add(switch (shape) {
                case "circle"  -> new DrawingOp("circle",  x, y, size, size, null, fc, sc, 2, null, null);
                case "rect"    -> new DrawingOp("rect",    x - size/2, y - size/2, size, size, null, fc, sc, 2, null, null);
                case "line"    -> new DrawingOp("line",    x, y, x + size, y, null, null, sc, 2, null, null);
                case "triangle"-> new DrawingOp("triangle",x, y - size/2, size, size, null, fc, sc, 2, null, null);
                case "ellipse" -> new DrawingOp("ellipse", x, y, size, size * 0.7, null, fc, sc, 2, null, null);
                case "undo"    -> new DrawingOp("undo",  0, 0, 0, 0, null, null, null, 0, null, null);
                case "clear"   -> new DrawingOp("clear", 0, 0, 0, 0, null, null, null, 0, null, null);
                default        -> new DrawingOp("circle", x, y, size, size, null, fc, sc, 2, null, null);
            });
        }
        return result;
    }
}
