package com.voicedraw.service.strategy;

import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesis;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisParam;
import com.alibaba.dashscope.aigc.imagesynthesis.ImageSynthesisResult;
import com.voicedraw.config.VoiceDrawProperties;
import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.SemanticOp;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

@Component
public class ImageGenStrategy implements IntentStrategy {

    private final VoiceDrawProperties props;

    public ImageGenStrategy(VoiceDrawProperties props) { this.props = props; }

    @Override public String type() { return "image"; }

    @Override
    public List<DrawingOp> execute(List<SemanticOp> ops) {
        for (SemanticOp op : ops) {
            String imageUrl = generate(buildPrompt(op));
            if (imageUrl != null)
                return List.of(new DrawingOp("image", 0, 0, 512, 512, null, null, null, 0, null, imageUrl));
        }
        return Collections.emptyList();
    }

    private String buildPrompt(SemanticOp op) {
        StringBuilder sb = new StringBuilder(op.text() != null ? op.text() : "a simple drawing");
        if (op.color() != null) sb.append(", ").append(op.color()).append(" colored");
        if (op.size() != null) sb.append(", ").append(op.size()).append(" size");
        return sb.append(", simple vector style, white background").toString();
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
}
