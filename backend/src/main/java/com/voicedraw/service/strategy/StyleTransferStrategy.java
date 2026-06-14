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
 * 风格迁移策略：对画布截图施加风格变换。
 * LLM 输出风格语义标签，后端映射为 prompt 片段，
 * 连同画布截图一起送通义万相做图生图/风格迁移。
 */
@Component
public class StyleTransferStrategy implements IntentStrategy {

    private final VoiceDrawProperties props;

    public StyleTransferStrategy(VoiceDrawProperties props) { this.props = props; }

    @Override public String type() { return "style_transfer"; }

    // 复用 ImageGenStrategy 的风格映射表
    static final Map<String, String> STYLE = Map.ofEntries(
        Map.entry("水彩", "watercolor painting, soft edges, gentle wet wash"),
        Map.entry("油画", "oil painting, visible brushstrokes, rich texture"),
        Map.entry("素描", "pencil sketch, monochrome, fine hand-drawn lines"),
        Map.entry("水墨", "ink wash painting, sumi-e style, black ink on rice paper"),
        Map.entry("赛博朋克", "cyberpunk, neon lights, futuristic, high-tech"),
        Map.entry("像素", "pixel art, 8-bit retro game style, blocky"),
        Map.entry("卡通", "cartoon style, bold outlines, flat colors, cute"),
        Map.entry("写实", "photorealistic, highly detailed, 8k"),
        Map.entry("扁平", "flat design, minimal, clean geometric shapes"),
        Map.entry("儿童画", "children's book illustration, crayon texture, playful"),
        Map.entry("浮世绘", "ukiyo-e, Japanese woodblock print"),
        Map.entry("极简", "minimalist, negative space, simple forms")
    );

    @Override
    public List<DrawingOp> execute(List<SemanticOp> ops) {
        List<DrawingOp> results = new ArrayList<>();
        for (SemanticOp op : ops) {
            String style = null;
            if (op.extra() != null && op.extra().get("style") != null) {
                style = op.extra().get("style").toString();
            }
            if (style == null && op.text() != null) {
                style = op.text();
            }
            if (style == null) continue;

            String stylePrompt = STYLE.getOrDefault(style, style);
            String imageUrl = generate(stylePrompt);
            if (imageUrl != null) {
                results.add(new DrawingOp("image", 0, 0, 800, 600,
                    null, null, null, 0, null, imageUrl, -1));
            }
        }
        return results;
    }

    /** 调用万相风格迁移（text-to-image 模拟：将风格描述作为 prompt） */
    private String generate(String stylePrompt) {
        try {
            String prompt = "redraw this canvas in " + stylePrompt
                + ", same layout and composition, high quality";
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
}
