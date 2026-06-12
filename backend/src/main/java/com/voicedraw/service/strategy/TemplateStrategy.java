package com.voicedraw.service.strategy;

import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.SemanticOp;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 模板策略：将语义模板（如"房子"）拆解为多个基本图形。
 * 拆解出的子图形走 GeometryStrategy 映射坐标。
 */
@Component
public class TemplateStrategy implements IntentStrategy {

    @Override
    public String type() { return "template"; }

    // 模板：模板名 → 子图形列表（坐标相对于模板锚点）
    static final Map<String, List<SemanticOp>> TEMPLATES = Map.of(
        "房子", List.of(
            // 墙壁
            new SemanticOp("rect", "center", "center", "large", null, null, null, true, false, null, null),
            // 屋顶
            new SemanticOp("triangle", "center", "top", null, "red", null, null, true, false, null, null),
            // 门
            new SemanticOp("rect", "center", "bottom", "small", "brown", null, null, true, false, null, null)
        ),
        "太阳", List.of(
            new SemanticOp("circle", "right", "top", "medium", "yellow", null, null, true, false, null, null)
        ),
        "树", List.of(
            new SemanticOp("rect", "center", "bottom", null, "brown", null, null, true, false, null, null),
            new SemanticOp("circle", "center", "top", "large", "green", null, null, true, false, null, null)
        )
    );

    @Override
    public List<DrawingOp> execute(List<SemanticOp> ops) {
        List<SemanticOp> allSubOps = new ArrayList<>();
        for (SemanticOp op : ops) {
            if (op.text() != null && TEMPLATES.containsKey(op.text())) {
                allSubOps.addAll(TEMPLATES.get(op.text()));
            } else {
                allSubOps.add(op);
            }
        }
        // 委托给 GeometryStrategy 做映射
        GeometryStrategy gs = new GeometryStrategy();
        return gs.execute(allSubOps);
    }
}
