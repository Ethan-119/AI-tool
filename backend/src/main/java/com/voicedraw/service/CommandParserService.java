package com.voicedraw.service;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.google.gson.Gson;
import com.voicedraw.config.VoiceDrawProperties;
import com.voicedraw.model.IntentResult;
import com.voicedraw.model.SemanticOp;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class CommandParserService {

    private final VoiceDrawProperties props;
    private final Gson gson = new Gson();

    private static final String SYSTEM_PROMPT = """
        你是一个语音绘图指令解析器。将用户的自然语言转换为 JSON 指令。

        输出格式（严格遵守，不要输出任何其他内容）：
        {
          "understood": true,
          "type": "geometry",
          "operations": [{
            "shape": "circle|rect|line|triangle|ellipse|undo|clear",
            "x": "left|center|right",
            "y": "top|middle|bottom",
            "size": "small|medium|large",
            "color": "red|blue|green|yellow|black|white|orange|purple|pink|gray|brown|cyan",
            "fill": true,
            "stroke": false
          }]
        }

        规则：
        - type: 如果是画具体形状用 "geometry"，如果是画抽象概念（猫/房子/汽车）用 "image"，如果是模板概念（房子/太阳/树）用 "template"
        - 坐标系统（9 宫格）：x 用 left/center/right，y 用 top/middle/bottom
        - size: small=小, medium=中, large=大
        - 如果用户说"然后/接着/再/也"，拆成多个 operations
        - 如果完全无法理解，设 understood=false，clarification 写追问内容
        - 如果用户说"撤销"或"清空"或"全删"，shape 用 "undo" 或 "clear"，其他字段可省略
        """;

    public CommandParserService(VoiceDrawProperties props) { this.props = props; }

    public IntentResult parse(String text) {
        try {
            GenerationParam param = GenerationParam.builder()
                .apiKey(props.getApiKey())
                .model(props.getLlm().getModel())
                .temperature((float) props.getLlm().getTemperature())
                .messages(List.of(
                    Message.builder().role(Role.SYSTEM.getValue()).content(SYSTEM_PROMPT).build(),
                    Message.builder().role(Role.USER.getValue()).content(text).build()
                ))
                .build();
            GenerationResult result = new Generation().call(param);
            String response = result.getOutput().getChoices().get(0).getMessage().getContent();
            String json = response.trim();
            if (json.startsWith("```")) json = json.replaceAll("```json|```", "").trim();
            IntentResult ir = gson.fromJson(json, IntentResult.class);
            if (ir != null && ir.understood()) return ir;
            if (ir != null && !ir.understood()) return ir;
        } catch (Exception e) { /* fall through to fallback */ }
        return fallback(text);
    }

    private IntentResult fallback(String text) {
        String shape = detectShape(text), color = detectColor(text), x = detectX(text), y = detectY(text), size = detectSize(text), type = "geometry";
        if (text.contains("猫") || text.contains("狗") || text.contains("花") || text.contains("风景")) type = "image";
        if (text.contains("房子") || text.contains("太阳") || text.contains("树")) type = "template";
        if (text.contains("撤销")) shape = "undo";
        if (text.contains("清空") || text.contains("全删")) shape = "clear";
        return new IntentResult(true, null, type, List.of(new SemanticOp(shape, x, y, size, color, null, null, true, false, null, null)));
    }
    private String detectShape(String t) { if (t.contains("椭圆")) return "ellipse"; if (t.contains("圆")) return "circle"; if (t.contains("方") || t.contains("矩")) return "rect"; if (t.contains("三角")) return "triangle"; if (t.contains("线")) return "line"; return "circle"; }
    private String detectColor(String t) { for (String c : new String[]{"红","蓝","绿","黄","黑","白","橙","紫","粉","灰","棕","青","red","blue","green","yellow","black","white","orange","purple","pink","gray","brown","cyan"}) if (t.contains(c)) return c; return null; }
    private String detectX(String t) { if (t.contains("左")) return "left"; if (t.contains("右")) return "right"; return "center"; }
    private String detectY(String t) { if (t.contains("上") || t.contains("顶")) return "top"; if (t.contains("下") || t.contains("底")) return "bottom"; return "middle"; }
    private String detectSize(String t) { if (t.contains("小")) return "small"; if (t.contains("大")) return "large"; return "medium"; }
}
