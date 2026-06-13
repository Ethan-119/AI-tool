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
            "color": "红色",
            "fillColor": "蓝色",
            "strokeColor": "绿色"
          }]
        }

        === L1 基础绘图 ===

        示例1: "在左上画一个红色三角形"
        → {"type":"geometry","operations":[{"shape":"triangle","x":"left","y":"top","color":"红","fill":true}]}

        示例2: "在中间画蓝色大圆，在右下画绿色小方"
        → {"type":"geometry","operations":[
          {"shape":"circle","x":"center","y":"middle","size":"large","color":"蓝"},
          {"shape":"rect","x":"right","y":"bottom","size":"small","color":"绿"}
        ]}

        === L2 上下文理解和修改 ===

        示例3（指代引用）: "把它涂成绿色"
        → "它/这个/那个"的指代对象需根据对话上下文判断。通常是最近被提到或修改的图形
        → 例如：上一轮说了"把三角形改成蓝色"，则"它"=三角形。如果上下文没有明确指向，则默认为最后一个图形（最大index）
        → {"type":"modify","operations":[{"shape":"modify","targetIndex":根据上下文确定的index,"fillColor":"绿"}]}

        示例4（形状引用）: "把三角形改成蓝色"（画布上下文会列出已有图形及其 index，从中找到三角形的 index）
        → {"type":"modify","operations":[{"shape":"modify","targetIndex":三角形的index,"color":"蓝"}]}

        示例5（复合+修改）: "在左上画红色三角，然后把三角改成蓝色"
        → 先画三角(index=0)，再改它 → targetIndex=0
        → {"type":"geometry","operations":[
          {"shape":"triangle","x":"left","y":"top","color":"红","fill":true},
          {"shape":"modify","targetIndex":0,"color":"蓝"}
        ]}

        示例6（填充+描边）: "填充红色，描边加粗"（没指定具体图形 → 改最后一个）
        → {"type":"modify","operations":[{"shape":"modify","targetIndex":最大的index,"fillColor":"红","strokeColor":"红","stroke":true}]}

        示例7（批量修改-有条件）: "把左边的都改成红色"
        → 含"都/全部/所有"=批量，可不填 targetIndex，系统自动匹配
        → {"type":"modify","operations":[{"shape":"modify","color":"红"}]}

        示例8（批量修改-全选）: "把所有图形改成蓝色" / "把全部图形涂红"
        → 无具体筛选条件=全选，不填 targetIndex，系统自动匹配所有图形
        → {"type":"modify","operations":[{"shape":"modify","color":"蓝"}]}

        示例9（多条件精确匹配）: "把中间的绿色圆形改成蓝色"
        → 从上下文找"中间位置+绿色+圆形"的图形，填其 index
        → {"type":"modify","operations":[{"shape":"modify","targetIndex":匹配的index,"color":"蓝"}]}

        示例（修改-颜色指代）: "把红色的那个改成蓝色"
        → 从上下文找到颜色=红的图形，填其 index。如有多个红色图形则结合其他条件缩小范围，无法确定时可不填 targetIndex
        → {"type":"modify","operations":[{"shape":"modify","targetIndex":匹配的index,"color":"蓝"}]}

        === L2 相对定位绘图 ===

        示例10（相对定位-指定形状）: "在矩形的正下面画一个三角形"（画布上下文中有矩形，从中找到其位置）
        → 找到矩形在上下文中的位置。正下面=水平对齐矩形、垂直在矩形下方。
        → {"type":"geometry","operations":[{"shape":"triangle","x":"left","y":"bottom","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"below"}}]}

        示例11（相对定位-指代）: "在它的正上方画一个圆"（"它"=对话上下文中最近讨论的图形）
        → 找到最后一个图形的index和位置。正上方=水平对齐、垂直在上方。
        → {"type":"geometry","operations":[{"shape":"circle","x":"center","y":"top","fill":true,"extra":{"relativeTo":"它","relativeDir":"above"}}]}

        示例12（相对定位-复合）: "在左上方画一个矩形，然后在矩形的正右方画一个三角形"
        → 先画矩形(index=0,位置=左上)，再在矩形的正右方画三角形
        → {"type":"geometry","operations":[
          {"shape":"rect","x":"left","y":"top","fill":true},
          {"shape":"triangle","x":"right","y":"top","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"right"}}
        ]}

        示例13（相对定位-位置指代）: "在左上方这个图形的正下方画一个三角形"
        → 从画布上下文找到"左上"位置的图形（如index=0是矩形），relativeTo填其形状名
        → {"type":"geometry","operations":[{"shape":"triangle","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"below"}}]}
        → 注意：用户没指定颜色和大小，不要从参考图形复制，保持默认即可

        示例14（相对定位-颜色指代）: "在红色的那个图形正下方画一个三角形"
        → 从画布上下文找到颜色=红的图形（如index=0是矩形），relativeTo填其形状名
        → {"type":"geometry","operations":[{"shape":"triangle","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"below"}}]}

        注意：
        - 参考图形可以通过形状名（"矩形"/"圆"）、指代词（"它"）、位置（"左上方那个"）、颜色（"红色的那个"）、大小（"大的那个"）、或以上任意组合来指定
        - 无论用户用什么方式描述，你都必须从画布上下文中找到对应图形，然后把它的形状名（或"它"）填入 relativeTo
        - relativeTo 只能填形状名或"它"，绝对不能填位置词/颜色词/大小词
        - 系统会根据参考图形的实际坐标精确计算位置，不走9宫格估算
        - 参考图形名必须能在画布上下文中找到，否则无法定位

        规则：
        - 画新图形 → type="geometry"，包含坐标/大小/颜色，不填 targetIndex
        - 修改已有图形（改颜色/填充/描边）→ type="modify"
        - "它/这个/那个"的指代对象需根据对话上下文判断（最近被提到/修改的图形）。只有上下文无法判断时才默认为最后一个图形
        - 提到具体形状名（三角/圆/椭圆/矩形/线）→ 从画布上下文查对应图形的 index。有多个同名图形时结合位置/颜色/大小缩小范围，无法确定时选最近被讨论的那个
        - 单个图形修改 → targetIndex 必填。批量修改（含"都/全部/所有"）→ 可不填 targetIndex，系统自动匹配。无具体筛选条件时=全选所有图形
        - 一句话含多个动作（逗号/句号/然后/接着/再/和）→ 拆成多个 operation，可混合 geometry 和 modify
        - 抽象概念（猫/狗/花/风景）→ type="image"。模板（房子/太阳/树）→ type="template"
        - 坐标9宫格：x=left/center/right，y=top/middle/bottom。size: small/medium/large
        - 忽略"屏幕上""画布上""画面中"等修饰词，它们不影响坐标判断
        - 用户没指定的属性（颜色/大小）不要推断或从参考图形复制，保持默认（黑/中）
        - 撤销→ shape="undo"，清空→ shape="clear"
        - 完全无法理解→ understood=false，clarification 写追问
        """;

    public CommandParserService(VoiceDrawProperties props) { this.props = props; }

    public IntentResult parse(String text, String canvasContext) {
        try {
            String systemPrompt = SYSTEM_PROMPT;
            if (canvasContext != null && !canvasContext.isBlank()) {
                systemPrompt += "\n\n当前画布上已有以下图形（index 从0开始）：\n" + canvasContext
                    + "\n如果用户说\"它\"\"那个\"\"这个\"或指代性词语，请根据对话上下文判断指代哪个图形（通常是最近被提到或修改的那个）。无法判断时默认最后一个。";
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
    private String detectShape(String t) { if (t.contains("椭圆")) return "ellipse"; if (t.contains("三角")) return "triangle"; if (t.contains("矩形") || t.contains("正方")) return "rect"; if (t.contains("圆")) return "circle"; if (t.contains("线")) return "line"; return "circle"; }
    private String detectColor(String t) { for (String c : new String[]{"红","蓝","绿","黄","黑","白","橙","紫","粉","灰","棕","青","red","blue","green","yellow","black","white","orange","purple","pink","gray","brown","cyan"}) if (t.contains(c)) return c; return null; }
    private String detectX(String t) { if (t.contains("左")) return "left"; if (t.contains("右")) return "right"; return "center"; }
    private String detectY(String t) { if (t.contains("上") || t.contains("顶")) return "top"; if (t.contains("下") || t.contains("底")) return "bottom"; return "middle"; }
    private String detectSize(String t) { if (t.contains("小")) return "small"; if (t.contains("大")) return "large"; return "medium"; }
}
