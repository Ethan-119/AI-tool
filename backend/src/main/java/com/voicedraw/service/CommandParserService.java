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

        画新图形时的输出格式(geometry)：
        {
          "understood": true,
          "type": "geometry",
          "operations": [{
            "shape": "circle|rect|line|triangle|ellipse",
            "x": "left|center|right",
            "y": "top|middle|bottom",
            "size": "small|medium|large",
            "color": "颜色名",
            "fillColor": "填充颜色（可选）",
            "strokeColor": "描边颜色（可选）",
            "fill": true,
            "stroke": false
          }]
        }

        修改已有图形时的输出格式(modify)：
        {
          "understood": true,
          "type": "modify",
          "operations": [{
            "shape": "modify",
            "targetIndex": 0,
            "color": "green",
            "fillColor": "red"
          }]
        }

        示例1: "在左上画一个红色三角形"
        → {"type":"geometry","operations":[{"shape":"triangle","x":"left","y":"top","color":"红","fill":true}]}

        示例2: "在中间画蓝色大圆，在右下画绿色小方"
        → {"type":"geometry","operations":[
          {"shape":"circle","x":"center","y":"middle","size":"large","color":"蓝"},
          {"shape":"rect","x":"right","y":"bottom","size":"small","color":"绿"}
        ]}

        示例3: "把它改成紫色"
        → {"type":"modify","operations":[{"shape":"modify","targetIndex":0,"color":"紫"}]}

        规则：
        - 画新图形/新形状 → type="geometry"，包含坐标、大小、颜色等信息。不要填 targetIndex！
        - 改颜色("涂红"/"改绿色")、填充("填充蓝色")、描边 → type="modify"，targetIndex 必填
        - targetIndex 指向画布上下文中要修改的那个图形的 index。如果用户说"把它"，指最后一个图形（最大index）。如果只有唯一一个图形就填0。
        - 抽象概念（猫/狗/花/风景）→ type="image"，模板（房子/太阳/树）→ type="template"
        - 坐标9宫格：x=left/center/right，y=top/middle/bottom
        - size: small=小, medium=中, large=大
        - 如果一句话包含多个绘画动作（用"然后/接着/再/也/和/还有"或逗号句号连接），必须拆成多个 operations。例如"在左上画三角，在右下画椭圆"应输出 2 个 operation
        - 撤消→ shape="undo"，清空→ shape="clear"
        - 完全无法理解→ understood=false，clarification 写追问
        """;

    public CommandParserService(VoiceDrawProperties props) { this.props = props; }

    public IntentResult parse(String text, String canvasContext) {
        try {
            String systemPrompt = SYSTEM_PROMPT;
            if (canvasContext != null && !canvasContext.isBlank()) {
                systemPrompt += "\n\n当前画布上已有以下图形（index 从0开始）：\n" + canvasContext
                    + "\n如果用户说\"它\"\"那个\"\"这个\"或指代性词语，请根据画布上下文找到对应图形的 index。"
                    + "\n如果用户说\"填充\"\"改颜色\"\"涂\"且没指定具体图形，默认修改最后一个(index最大的)。";
            }
            GenerationParam param = GenerationParam.builder()
                .apiKey(props.getApiKey())
                .model(props.getLlm().getModel())
                .temperature((float) props.getLlm().getTemperature())
                .messages(List.of(
                    Message.builder().role(Role.SYSTEM.getValue()).content(systemPrompt).build(),
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
        } catch (Exception e) { /* fall through */ }
        return fallback(text);
    }

    private IntentResult fallback(String text) {
        // 复合指令拆分
        String[] parts = text.split("，|,|、|然后|接着|还有|再|和|及|与");
        if (parts.length > 1) {
            List<SemanticOp> ops = new java.util.ArrayList<>();
            for (String part : parts) {
                String s = part.trim();
                if (s.isEmpty()) continue;
                if (s.contains("撤销")) { ops.add(new SemanticOp("undo", null, null, null, null, null, null, false, false, null, null, null)); continue; }
                if (s.contains("清空") || s.contains("全删")) { ops.add(new SemanticOp("clear", null, null, null, null, null, null, false, false, null, null, null)); continue; }
                ops.add(new SemanticOp(detectShape(s), detectX(s), detectY(s), detectSize(s), detectColor(s), null, null, true, false, null, null, null));
            }
            if (!ops.isEmpty()) return new IntentResult(true, null, "geometry", ops);
        }

        String shape = detectShape(text), color = detectColor(text),
            x = detectX(text), y = detectY(text), size = detectSize(text), type = "geometry";
        if (text.contains("猫") || text.contains("狗") || text.contains("花") || text.contains("风景")) type = "image";
        if (text.contains("房子") || text.contains("太阳") || text.contains("树")) type = "template";
        if (text.contains("撤销")) shape = "undo";
        if (text.contains("清空") || text.contains("全删")) shape = "clear";
        if (text.contains("填充") || text.contains("改") || text.contains("涂")
            || text.contains("变成") || text.contains("换成")) {
            type = "modify"; shape = "modify";
            return new IntentResult(true, null, type, List.of(
                new SemanticOp(shape, null, null, null, color, null, null, false, false, null, null, null)));
        }
        return new IntentResult(true, null, type, List.of(
            new SemanticOp(shape, x, y, size, color, null, null, true, false, null, null, null)));
    }
    private String detectShape(String t) { if (t.contains("椭圆")) return "ellipse"; if (t.contains("圆")) return "circle"; if (t.contains("方") || t.contains("矩")) return "rect"; if (t.contains("三角")) return "triangle"; if (t.contains("线")) return "line"; return "circle"; }
    private String detectColor(String t) { for (String c : new String[]{"红","蓝","绿","黄","黑","白","橙","紫","粉","灰","棕","青","red","blue","green","yellow","black","white","orange","purple","pink","gray","brown","cyan"}) if (t.contains(c)) return c; return null; }
    private String detectX(String t) { if (t.contains("左")) return "left"; if (t.contains("右")) return "right"; return "center"; }
    private String detectY(String t) { if (t.contains("上") || t.contains("顶")) return "top"; if (t.contains("下") || t.contains("底")) return "bottom"; return "middle"; }
    private String detectSize(String t) { if (t.contains("小")) return "small"; if (t.contains("大")) return "large"; return "medium"; }
}
