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
import java.util.Map;

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
        15b. "在三角形的上方画红色矩形" → 参照物和要画的形状不同，不要混淆 → {"type":"geometry","operations":[{"shape":"rect","color":"红","fill":true,"extra":{"relativeTo":"三角","relativeDir":"above"}}]}
        15c. "在椭圆的右边画红色三角" → {"type":"geometry","operations":[{"shape":"triangle","color":"红","fill":true,"extra":{"relativeTo":"椭圆","relativeDir":"right"}}]}

        规则：
        - geometry=画新图形，不填targetIndex。modify=改变已有图形属性（改/变成/变为/换成/更换/调整/设置/填充/涂 + 颜色/大小/描边），shape必须填"modify"
        - "它/这个/那个"=上下文里"最近操作"标记的图形，未标记时默认最后一个
        - 提到形状名（三角/圆/椭圆/矩形/线）→ 从上下文查index；多个同名时结合位置/颜色/大小缩小范围
        - 批量（都/全部/所有）→ 可不填targetIndex；无筛选条件=全选
        - 方向描述（正上方/正下方/正左方/正右方/的左边/的右边/的上面/的下面）→ extra中必填relativeTo（形状名或"它"）和relativeDir（above/below/left/right）
        - relativeTo只能填形状名或"它"；参考图形可以是形状/位置/颜色/大小任意方式指代，但必须翻译成形状名
        - 要画的形状以用户说的为准，不要受参照物形状影响（"在三角上方画矩形"→新图形是rect不是triangle）
        - 多动作（逗号/句号/然后/接着/再/和）→ 拆成多个operation，可混合geometry和modify
        - 坐标：x=left/center/right, y=top/middle/bottom。size: small/medium/large。忽略"屏幕/画布/画面"等修饰词
        - 未指定属性不要推断或从参考复制，保持默认（黑/中）。抽象概念→image。模板→template
        - 撤销=undo，清空=clear。无法理解→understood=false
        """;

    private static final String L3_APPENDIX = """

        --- 以下是创意生图/风格变换的额外格式（仅当前面规则不适用时才用）---
        image: {"understood":true,"type":"image","operations":[{"text":"主体描述","x":"left|center|right","y":"top|middle|bottom","size":"small|medium|large","extra":{"style":"风格名","colorPalette":"色调名"可选}}]}
        style_transfer: {"understood":true,"type":"style_transfer","operations":[{"extra":{"style":"风格名"}}]}
        示例：
        "在中间画一只水彩风格的猫" → {"type":"image","operations":[{"text":"一只猫","x":"center","y":"middle","extra":{"style":"水彩"}}]}
        "把画布变成素描风格" → {"type":"style_transfer","operations":[{"extra":{"style":"素描"}}]}
        extra里的style/colorPalette只填用户明确提到的词，不要脑补。""";

    public CommandParserService(VoiceDrawProperties props) { this.props = props; }

    public IntentResult parse(String text, String canvasContext) {
        String systemPrompt = buildPrompt(canvasContext, text);

        // 第一层：单次 LLM 调用
        IntentResult ir = callLLM(text, systemPrompt);
        if (ir != null) return fixShapes(ir, text);

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
            if (!allOps.isEmpty()) return fixShapes(new IntentResult(true, null, "geometry", allOps), text);
        }

        // 第三层：关键词兜底
        return fallback(text);
    }

    private String buildPrompt(String canvasContext, String userText) {
        String prompt = SYSTEM_PROMPT;
        // L3 创意生图/风格转移：仅在用户提到相关关键词时追加格式说明
        if (needsL3(userText)) {
            prompt += L3_APPENDIX;
        }
        if (canvasContext != null && !canvasContext.isBlank()) {
            prompt += "\n\n当前画布上已有以下图形：\n" + canvasContext
                + "\n\"最近操作\"标记的是上一次被操作（新增或修改）的图形。\"它/他/这个/那个\"默认指它。";
        }
        return prompt;
    }

    /** 纠正 LLM 形状偏置：按文本中形状出现顺序逐个匹配，纠正不匹配的 shape */
    private IntentResult fixShapes(IntentResult ir, String text) {
        if (!"geometry".equals(ir.type()) || ir.operations() == null) return ir;
        List<String> expected = detectAllShapes(text);
        if (expected.isEmpty()) return ir;

        List<SemanticOp> ops = new ArrayList<>(ir.operations());
        // 统计实际要纠正的 op 数量（跳过 modify/undo/clear）
        int drawCount = 0;
        for (SemanticOp op : ops)
            if (!"modify".equals(op.shape()) && !"undo".equals(op.shape()) && !"clear".equals(op.shape()))
                drawCount++;
        // 单绘制操作：取文本中最后一个形状（用户的"画XX"），避免参照物的形状干扰
        // 多绘制操作：按出现顺序匹配（复合指令如"画三角和矩形"）
        boolean single = drawCount == 1;
        int changed = 0, si = 0;
        for (int i = 0; i < ops.size() && si < expected.size(); i++) {
            SemanticOp op = ops.get(i);
            if ("modify".equals(op.shape()) || "undo".equals(op.shape()) || "clear".equals(op.shape()))
                continue;
            String correct = single ? expected.get(expected.size() - 1) : expected.get(si++);
            if (!correct.equals(op.shape())) {
                ops.set(i, new SemanticOp(correct, op.x(), op.y(), op.size(), op.color(),
                    op.fillColor(), op.strokeColor(), op.fill(), op.stroke(),
                    op.text(), op.extra(), op.targetIndex()));
                changed++;
            }
        }
        if (changed > 0)
            System.out.println("[DEBUG] Fixed " + changed + " shape mismatch(es)");
        return new IntentResult(ir.understood(), ir.clarification(), ir.type(), ops);
    }

    /** 按文本中出现顺序提取所有形状关键词（"椭圆"优先于"圆"避免重叠匹配） */
    private List<String> detectAllShapes(String t) {
        record Hit(String shape, int pos) {}
        List<Hit> hits = new ArrayList<>();
        if (t.contains("椭圆")) { int p = t.indexOf("椭圆"); hits.add(new Hit("ellipse", p)); }
        if (t.contains("三角")) { int p = t.indexOf("三角"); hits.add(new Hit("triangle", p)); }
        if (t.contains("矩形") || t.contains("正方")) {
            int p = Math.min(t.contains("矩形") ? t.indexOf("矩形") : 999, t.contains("正方") ? t.indexOf("正方") : 999);
            hits.add(new Hit("rect", p));
        }
        int cp = t.indexOf("圆");
        if (cp >= 0 && (cp == 0 || t.charAt(cp - 1) != '椭')) hits.add(new Hit("circle", cp));
        int lp = t.indexOf("线");
        if (lp >= 0) hits.add(new Hit("line", lp));
        hits.sort((a, b) -> Integer.compare(a.pos, b.pos));
        List<String> result = new ArrayList<>();
        for (Hit h : hits) result.add(h.shape);
        return result;
    }

    private boolean needsL3(String text) {
        return text.contains("风格") || text.contains("画一只") || text.contains("画一幅")
            || text.contains("画一张") || text.contains("画个") || text.contains("画布")
            || text.contains("画画") || text.contains("水彩") || text.contains("油画")
            || text.contains("素描") || text.contains("水墨") || text.contains("赛博朋克")
            || text.contains("像素") || text.contains("卡通") || text.contains("写实")
            || text.contains("扁平") || text.contains("儿童画") || text.contains("浮世绘");
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
            if (ir != null && ir.understood() && ir.operations() != null && !ir.operations().isEmpty()) return ir;
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
        if (text.contains("猫") || text.contains("狗") || text.contains("花") || text.contains("风景")
            || text.contains("动物") || text.contains("山") || text.contains("海") || text.contains("星空")
            || text.contains("城市") || text.contains("森林") || text.contains("日落")) type = "image";
        if (text.contains("风格") && (text.contains("画布") || text.contains("变成") || text.contains("换成")))
            type = "style_transfer";
        if (text.contains("房子") || text.contains("太阳") || text.contains("树")) type = "template";
        if (text.contains("撤销")) shape = "undo";
        if (text.contains("清空") || text.contains("全删")) shape = "clear";
        if (text.contains("填充") || text.contains("改") || text.contains("涂")
            || text.contains("变成") || text.contains("换成")) {
            type = "modify"; shape = "modify";
            return new IntentResult(true, null, type, List.of(
                new SemanticOp(shape, null, null, null, color, null, null, false, false, null, null, null)));
        }
        if ("image".equals(type)) {
            String style = detectStyle(text);
            Map<String, Object> extra = style != null ? Map.of("style", style) : null;
            return new IntentResult(true, null, type, List.of(
                new SemanticOp(null, x, y, size, color, null, null, true, false, text, extra, null)));
        }
        if ("style_transfer".equals(type)) {
            String style = detectStyle(text);
            return new IntentResult(true, null, type, List.of(
                new SemanticOp(null, null, null, null, null, null, null, false, false, null,
                    style != null ? Map.of("style", style) : null, null)));
        }
        return new IntentResult(true, null, type, List.of(
            new SemanticOp(shape, x, y, size, color, null, null, true, false, null, null, null)));
    }
    private String detectShape(String t) { if (t.contains("椭圆")) return "ellipse"; if (t.contains("三角")) return "triangle"; if (t.contains("矩形") || t.contains("正方")) return "rect"; if (t.contains("圆")) return "circle"; if (t.contains("线")) return "line"; return "circle"; }
    private String detectColor(String t) { for (String c : new String[]{"红","蓝","绿","黄","黑","白","橙","紫","粉","灰","棕","青","red","blue","green","yellow","black","white","orange","purple","pink","gray","brown","cyan"}) if (t.contains(c)) return c; return null; }
    private String detectX(String t) { if (t.contains("左")) return "left"; if (t.contains("右")) return "right"; return "center"; }
    private String detectY(String t) { if (t.contains("上") || t.contains("顶")) return "top"; if (t.contains("下") || t.contains("底")) return "bottom"; return "middle"; }
    private String detectSize(String t) { if (t.contains("小")) return "small"; if (t.contains("大")) return "large"; return "medium"; }
    private String detectStyle(String t) {
        for (String s : new String[]{"水彩","油画","素描","水墨","赛博朋克","像素","卡通","写实","扁平","儿童画","浮世绘","极简"})
            if (t.contains(s)) return s;
        return null;
    }
}
