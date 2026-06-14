package com.voicedraw.service.strategy;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.voicedraw.config.VoiceDrawProperties;
import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.SemanticOp;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * AI 生图策略：LLM 输出 L3 语义标签（风格/氛围/色调/构图），
 * 后端查映射表拼装高质量 prompt，调通义万相生成图像。
 * 位置/大小仍走九宫格映射，保证与 L1/L2 统一的定位体验。
 */
@Component
public class ImageGenStrategy implements IntentStrategy {

    private final VoiceDrawProperties props;

    public ImageGenStrategy(VoiceDrawProperties props) { this.props = props; }

    @Override public String type() { return "image"; }

    // ---- L3 语义 → prompt 片段映射 ----

    static final Map<String, String> STYLE = Map.ofEntries(
        Map.entry("水彩", "watercolor painting, soft edges, gentle wet wash, translucent layers"),
        Map.entry("油画", "oil painting, visible brushstrokes, rich impasto texture"),
        Map.entry("素描", "pencil sketch, fine lines, monochrome, hand-drawn"),
        Map.entry("水墨", "ink wash painting, sumi-e style, black ink on rice paper"),
        Map.entry("赛博朋克", "cyberpunk, neon lights, futuristic city, high-tech low-life"),
        Map.entry("像素", "pixel art, 8-bit retro game style, blocky"),
        Map.entry("卡通", "cartoon style, bold outlines, flat colors, cute"),
        Map.entry("写实", "photorealistic, highly detailed, 8k"),
        Map.entry("扁平", "flat design, minimal, clean geometric shapes"),
        Map.entry("儿童画", "children's book illustration, crayon texture, playful"),
        Map.entry("浮世绘", "ukiyo-e, Japanese woodblock print, flat colors"),
        Map.entry("极简", "minimalist, negative space, simple forms")
    );

    static final Map<String, String> MOOD = Map.ofEntries(
        Map.entry("温暖", "warm and cozy atmosphere"),
        Map.entry("冷静", "calm and serene mood"),
        Map.entry("欢快", "cheerful, bright and energetic"),
        Map.entry("忧郁", "melancholy, subdued and pensive"),
        Map.entry("浪漫", "romantic, soft lighting, dreamy"),
        Map.entry("神秘", "mysterious, dark ambiance with subtle light"),
        Map.entry("活泼", "vibrant, lively, full of movement"),
        Map.entry("宁静", "peaceful, tranquil, zen-like stillness")
    );

    static final Map<String, String> PALETTE = Map.ofEntries(
        Map.entry("暖色系", "warm color palette, reds oranges yellows"),
        Map.entry("冷色系", "cool color palette, blues greens purples"),
        Map.entry("莫兰迪", "muted desaturated Morandi palette, grayish tones"),
        Map.entry("高饱和", "highly saturated, vivid bold colors"),
        Map.entry("黑白", "black and white, monochrome, grayscale"),
        Map.entry("粉彩", "pastel colors, soft pinks and lavenders"),
        Map.entry("大地色", "earthy tones, browns greens terracotta"),
        Map.entry("金属色", "metallic sheen, gold silver bronze accents")
    );

    static final Map<String, String> COMPOSITION = Map.ofEntries(
        Map.entry("居中", "centered composition, symmetric"),
        Map.entry("三分法", "rule of thirds composition"),
        Map.entry("对角线", "dynamic diagonal composition"),
        Map.entry("散点", "scattered elements, playful arrangement")
    );

    @Override
    public List<DrawingOp> execute(List<SemanticOp> ops) {
        List<DrawingOp> results = new ArrayList<>();
        for (SemanticOp op : ops) {
            String imageUrl = generate(buildPrompt(op));
            if (imageUrl != null) {
                double size = mapL3Size(op.size());
                // 九宫格映射的是中心坐标，转为左上角，避免图像偏出画布
                double cx = GeometryStrategy.mapX(op.x());
                double cy = GeometryStrategy.mapY(op.y());
                double x = cx - size / 2;
                double y = cy - size / 2;
                results.add(new DrawingOp("image", x, y, size, size,
                    null, null, null, 0, null, imageUrl, -1));
            }
        }
        return results;
    }

    /** 将 L3 语义标签拼装为高质量 prompt */
    private String buildPrompt(SemanticOp op) {
        StringBuilder sb = new StringBuilder();

        // 主体描述
        if (op.text() != null && !op.text().isBlank()) {
            sb.append(op.text());
        } else {
            sb.append("a simple drawing");
        }

        // 风格
        if (op.extra() != null && op.extra().get("style") != null) {
            String s = op.extra().get("style").toString();
            sb.append(", ").append(STYLE.getOrDefault(s, s));
        }

        // 氛围
        if (op.extra() != null && op.extra().get("mood") != null) {
            String m = op.extra().get("mood").toString();
            sb.append(", ").append(MOOD.getOrDefault(m, m));
        }

        // 色调
        if (op.extra() != null && op.extra().get("colorPalette") != null) {
            String p = op.extra().get("colorPalette").toString();
            sb.append(", ").append(PALETTE.getOrDefault(p, p));
        }

        // 构图
        if (op.extra() != null && op.extra().get("composition") != null) {
            String c = op.extra().get("composition").toString();
            sb.append(", ").append(COMPOSITION.getOrDefault(c, c));
        }

        // 颜色提示
        if (op.color() != null) {
            String cn = GeometryStrategy.mapColor(op.color());
            if (cn != null && cn.startsWith("#")) {
                // hex → 英文色名（简易映射，主要色系）
                sb.append(", with ").append(hexToEng(cn)).append(" accents");
            } else {
                sb.append(", ").append(cn);
            }
        }

        sb.append(", simple clean composition, white background, high quality");
        return sb.toString();
    }

    private String generate(String prompt) {
        try {
            ImageSynthesisParam param = ImageSynthesisParam.builder()
                .apiKey(props.getApiKey())
                .model(props.getImage().getModel())
                .prompt(prompt).n(1).size(props.getImage().getSize())
                .build();
            ImageSynthesisResult result = new ImageSynthesis().call(param);
            if (result.getOutput() != null
                && result.getOutput().getResults() != null
                && !result.getOutput().getResults().isEmpty())
                return result.getOutput().getResults().get(0).get("url");
        } catch (Exception ignored) {}
        return null;
    }

    /** L3 图像尺寸映射（比几何图形大），默认 512 */
    private double mapL3Size(String size) {
        if (size == null) return 512;
        return switch (size) {
            case "small", "小" -> 300;
            case "large", "大" -> 700;
            default -> 512;
        };
    }

    private static String hexToEng(String hex) {
        if (hex == null) return "";
        return switch (hex.toUpperCase()) {
            case "#FF0000" -> "red";
            case "#0000FF" -> "blue";
            case "#00FF00" -> "green";
            case "#FFFF00" -> "yellow";
            case "#FF8800" -> "orange";
            case "#8800FF" -> "purple";
            case "#FF88CC" -> "pink";
            case "#00FFFF" -> "cyan";
            case "#000000" -> "black";
            case "#FFFFFF" -> "white";
            case "#888888" -> "gray";
            default -> "";
        };
    }
}
