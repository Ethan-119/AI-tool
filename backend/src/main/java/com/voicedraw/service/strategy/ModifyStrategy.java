package com.voicedraw.service.strategy;

import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.SemanticOp;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 修改策略：根据 targetIndex 修改画布上已有元素的属性。
 * 支持改颜色、大小等，前端收到 modify 类型 DrawingOp 后更新数组中对应元素。
 */
@Component
public class ModifyStrategy implements IntentStrategy {

    @Override
    public String type() { return "modify"; }

    @Override
    public List<DrawingOp> execute(List<SemanticOp> ops) {
        List<DrawingOp> result = new ArrayList<>();
        for (SemanticOp op : ops) {
            // targetIndex 为空时默认 -1，前端兜底改最后一个元素
            int idx = op.targetIndex() != null ? op.targetIndex() : -1;
            String newColor = op.color() != null
                ? GeometryStrategy.mapColor(op.color()) : null;
            String newFill = op.fillColor() != null
                ? GeometryStrategy.mapColor(op.fillColor()) : null;

            result.add(new DrawingOp("modify", 0, 0, 0, 0,
                newColor, newFill,
                null, 0, null, null, idx));
        }
        return result;
    }
}
