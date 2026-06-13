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

import java.util.ArrayList;
import java.util.List;

@Service
public class CommandParserService {

    private final VoiceDrawProperties props;
    private final Gson gson = new Gson();

    private static final String SYSTEM_PROMPT = """
        你是语音绘图解析器。输出JSON，格式：

        geometry: {"understood":true,"type":"geometry","operations":[{"shape":"circle|rect|line|triangle|ellipse","x":"left|center|right","y":"top|middle|bottom","size":"small|medium|large","color":"颜色","fillColor":"填充色","strokeColor":"描边色","fill":true,"stroke":false}]}
        modify:  {"understood":true,"type":"modify","operations":[{"shape":"modify","targetIndex":0,"color":"红","fillColor":"蓝"}]}

        示例：
        1. "在左上画红色三角" → {"type":"geometry","operations":[{"shape":"triangle","x":"left","y":"top","color":"红","fill":true}]}
        2. "在中间画蓝色大圆，右下画绿色小方" → {"type":"geometry","operations":[{"shape":"circle","x":"center","y":"middle","size":"large","color":"蓝"},{"shape":"rect","x":"right","y":"bottom","size":"small","color":"绿"}]}
        3. "把它涂成绿色" → "它/这个/那个"=最近被提到/修改的图形，上下文无法判断时默认最后一个 → {"type":"modify","operations":[{"shape":"modify","targetIndex":对应index,"fillColor":"绿"}]}
        4. "把三角形改成蓝色" → 从上下文找三角形的index → {"type":"modify","operations":[{"shape":"modify","targetIndex":对应index,"color":"蓝"}]}
        5. "在左上画红色三角，然后把三角改成蓝色" → 画完立即修改=type仍为geometry，修改那项shape填modify → {"type":"geometry","operations":[{"shape":"triangle","x":"left","y":"top","color":"红","fill":true},{"shape":"modify","targetIndex":0,"color":"蓝"}]}
        5b. "在中间画一个大圆，然后把它变成红色" → 同上，变成/换成/改为=modify → {"type":"geometry","operations":[{"shape":"circle","x":"center","y":"middle","size":"large","fill":true},{"shape":"modify","targetIndex":0,"color":"红"}]}
        6. "把左边的都改成红色" → 批量可不填targetIndex → {"type":"modify","operations":[{"shape":"modify","color":"红"}]}
        7. "把所有图形改成蓝色" → 全选不填targetIndex → {"type":"modify","operations":[{"shape":"modify","color":"蓝"}]}
        8. "把中间的绿色圆形改成蓝色" → 多条件精确匹配 → {"type":"modify","operations":[{"shape":"modify","targetIndex":对应index,"color":"蓝"}]}
        9. "在矩形的正下方画三角形" → 相对定位，extra必填 → {"type":"geometry","operations":[{"shape":"triangle","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"below"}}]}
        10. "在它的正上方画圆" → {"type":"geometry","operations":[{"shape":"circle","fill":true,"extra":{"relativeTo":"它","relativeDir":"above"}}]}
        11. "在左上方画矩形，然后在矩形的正右方画三角" → {"type":"geometry","operations":[{"shape":"rect","x":"left","y":"top","fill":true},{"shape":"triangle","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"right"}}]}
        12. "在三角形的左边画椭圆，右边画矩形" → 方向词也需extra → {"type":"geometry","operations":[{"shape":"ellipse","fill":true,"extra":{"relativeTo":"三角","relativeDir":"left"}},{"shape":"rect","fill":true,"extra":{"relativeTo":"三角","relativeDir":"right"}}]}
        13. "填充红色"（没指定图形→改最后一个）→ {"type":"modify","operations":[{"shape":"modify","targetIndex":最大的index,"fillColor":"红"}]}
        14. "在左上方那个图形的正下方画三角" → 从上下文找"左上"位置的图形，relativeTo填其形状名 → {"type":"geometry","operations":[{"shape":"triangle","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"below"}}]}
        15. "在红色的那个图形正下方画三角" → 从上下文找颜色=红的图形，relativeTo填其形状名 → {"type":"geometry","operations":[{"shape":"triangle","fill":true,"extra":{"relativeTo":"矩形","relativeDir":"below"}}]}

        规则：
        - geometry=画新图形，不填targetIndex。modify=改变已有图形属性（改/变成/变为/换成/更换/调整/设置/填充/涂 + 颜色/大小/描边），shape必须填"modify"
        - "它/这个/那个"=对话中最近被提到/修改的图形，无法判断时默认最后一个
        - 提到形状名（三角/圆/椭圆/矩形/线）→ 从上下文查index；多个同名时结合位置/颜色/大小缩小范围
        - 批量（都/全部/所有）→ 可不填targetIndex；无筛选条件=全选
        - 方向描述（正上方/正下方/正左方/正右方/的左边/的右边/的上面/的下面）→ extra中必填relativeTo（形状名或"它"）和relativeDir（above/below/left/right）
        - relativeTo只能填形状名或"它"；参考图形可以是形状/位置/颜色/大小任意方式指代，但必须翻译成形状名
        - 多动作（逗号/句号/然后/接着/再/和）→ 拆成多个operation，可混合geometry和modify
        - 坐标：x=left/center/right, y=top/middle/bottom。size: small/medium/large。忽略"屏幕/画布/画面"等修饰词
        - 未指定属性不要推断或从参考复制，保持默认（黑/中）。抽象概念→image。模板→template
        - 撤销=undo，清空=clear。无法理解→understood=false
        """;

    public CommandParserService(VoiceDrawProperties props) { this.props = props; }

    public IntentResult parse(String text, String canvasContext) {
        String systemPrompt = buildPrompt(canvasContext);

        // 第一层：单次 LLM 调用
        IntentResult ir = callLLM(text, systemPrompt);
        if (ir != null) return ir;

        // 第二层：拆句后分别调 LLM
        String[] parts = splitParts(text);
        if (parts.length > 1) {
            List<SemanticOp> allOps = new ArrayList<>();
            for (String part : parts) {
                IntentResult partResult = callLLM(part.trim(), systemPrompt);
                if (partResult != null && partResult.understood()) {
                    allOps.addAll(partResult.operations());
                }
            }
            if (!allOps.isEmpty()) return new IntentResult(true, null, "geometry", allOps);
        }

        // 第三层：关键词兜底
        return fallback(text);
    }

    private String buildPrompt(String canvasContext) {
        String prompt = SYSTEM_PROMPT;
        if (canvasContext != null && !canvasContext.isBlank()) {
            prompt += "\n\n当前画布上已有以下图形（index 从0开始）：\n" + canvasContext
                + "\n如果用户说\"它\"\"那个\"\"这个\"或指代性词语，请根据对话上下文判断指代哪个图形（通常是最近被提到或修改的那个）。无法判断时默认最后一个。";
        }
        return prompt;
    }

    private IntentResult callLLM(String text, String systemPrompt) {
        try {
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
        return null;
    }

    private String[] splitParts(String text) {
        String[] parts = text.split("，|。|,|\\.|然后|接着|还有|再|和|及|与");
        List<String> list = new ArrayList<>();
        for (String p : parts) if (!p.trim().isEmpty()) list.add(p.trim());
        return list.isEmpty() ? new String[]{text} : list.toArray(new String[0]);
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
