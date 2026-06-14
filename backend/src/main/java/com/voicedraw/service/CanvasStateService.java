package com.voicedraw.service;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.ElementState;
import com.voicedraw.model.SemanticOp;
import com.voicedraw.service.strategy.GeometryStrategy;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 画布状态管理：结构化存储、上下文格式化、多维度匹配、操作应用。
 */
@Service
public class CanvasStateService {

    private final RedisTemplate<String, Object> redisTemplate;
    private final Gson gson = new Gson();

    private static final String KEY_PREFIX = "voice:elements:";
    private static final int TTL_MINUTES = 30;

    private static final String[] POS_KEYS = {"左上","左下","右上","右下","左","右","上","下","中"};
    private static final String[] COLOR_KEYS = {"红","蓝","绿","黄","黑","白","橙","紫","粉","灰","棕","青"};
    private static final String[] SIZE_KEYS = {"大","小","中"};
    private static final String[] BATCH_KEYS = {"都","全部","所有","每个","各"};

    private static final Map<String, String> HEX_TO_NAME = Map.ofEntries(
        Map.entry("#FF0000","红"), Map.entry("#0000FF","蓝"), Map.entry("#00FF00","绿"),
        Map.entry("#FFFF00","黄"), Map.entry("#000000","黑"), Map.entry("#FFFFFF","白"),
        Map.entry("#FF8800","橙"), Map.entry("#8800FF","紫"), Map.entry("#FF88CC","粉"),
        Map.entry("#888888","灰"), Map.entry("#884400","棕"), Map.entry("#00FFFF","青")
    );

    public CanvasStateService(RedisTemplate<String, Object> redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    // ==================== Redis 存取 ====================

    public List<ElementState> getElements(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return new ArrayList<>();
        Object cached = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        if (cached == null) return new ArrayList<>();
        String json = cached.toString();
        List<ElementState> list = gson.fromJson(json, new TypeToken<List<ElementState>>(){}.getType());
        return list != null ? list : new ArrayList<>();
    }

    public void saveElements(String sessionId, List<ElementState> elements) {
        if (sessionId == null || sessionId.isBlank()) return;
        redisTemplate.opsForValue().set(KEY_PREFIX + sessionId, gson.toJson(elements), TTL_MINUTES, TimeUnit.MINUTES);
    }

    private static final String LAST_OP_KEY = "voice:lastop:";

    public void saveLastOpIndex(String sessionId, int idx) {
        if (sessionId == null || sessionId.isBlank()) return;
        redisTemplate.opsForValue().set(LAST_OP_KEY + sessionId, String.valueOf(idx), TTL_MINUTES, TimeUnit.MINUTES);
    }

    public Integer getLastOpIndex(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return null;
        Object v = redisTemplate.opsForValue().get(LAST_OP_KEY + sessionId);
        if (v == null) return null;
        try { return Integer.parseInt(v.toString()); } catch (NumberFormatException e) { return null; }
    }

    // ==================== 上下文格式化 ====================

    /**
     * 将结构化元素列表格式化为 LLM 上下文字符串，格式与前端 getElementsSummary() 一致。
     */
    public String formatContext(List<ElementState> elements) {
        if (elements == null || elements.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < elements.size(); i++) {
            ElementState el = elements.get(i);
            sb.append("[").append(el.index()).append("] ")
              .append(el.type()).append(" ")
              .append("x=").append((int) el.x()).append(" ")
              .append("y=").append((int) el.y()).append(" ")
              .append("位置=").append(el.position()).append(" ")
              .append("大小=").append(el.size()).append(" ")
              .append("颜色=").append(el.colorName()).append("\n");
        }
        return sb.toString();
    }

    // ==================== 状态更新 ====================

    /**
     * 根据 DrawingOp 列表更新画布元素状态，返回新的元素列表。
     */
    public List<ElementState> applyOps(List<ElementState> current, List<DrawingOp> ops) {
        List<ElementState> list = new ArrayList<>(current);
        for (int i = 0; i < ops.size(); i++) {
            DrawingOp op = ops.get(i);
            String type = op.type();
            if ("undo".equals(type)) {
                if (!list.isEmpty()) list.remove(list.size() - 1);
            } else if ("clear".equals(type)) {
                list.clear();
            } else if ("modify".equals(type)) {
                int idx = op.targetIndex();
                if (idx >= 0 && idx < list.size()) {
                    ElementState old = list.get(idx);
                    String newColor = op.color() != null ? op.color() : old.color();
                    String newFill = op.fillColor() != null ? op.fillColor() : old.fillColor();
                    String newStroke = op.strokeColor() != null ? op.strokeColor() : old.strokeColor();
                    String mainColor = newFill != null ? newFill : newColor;
                    list.set(idx, new ElementState(
                        idx, old.type(), old.x(), old.y(), old.width(), old.height(),
                        newColor, newFill, newStroke,
                        old.position(), old.size(), toColorName(mainColor)
                    ));
                }
            } else {
                int idx = list.size();
                String mainColor = op.fillColor() != null ? op.fillColor()
                    : (op.color() != null ? op.color() : "#000000");
                list.add(new ElementState(
                    idx, type, op.x(), op.y(), op.width(), op.height(),
                    op.color(), op.fillColor(), op.strokeColor(),
                    toPosition(op.x(), op.y()),
                    toSize(type, op.width(), op.height()),
                    toColorName(mainColor)
                ));
            }
        }
        // 重新编号
        List<ElementState> reindexed = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            ElementState el = list.get(i);
            reindexed.add(new ElementState(i, el.type(), el.x(), el.y(), el.width(), el.height(),
                el.color(), el.fillColor(), el.strokeColor(), el.position(), el.size(), el.colorName()));
        }
        return reindexed;
    }

    // ==================== 多维度匹配 ====================

    /**
     * 修正 modify 操作中缺少或越界的 targetIndex。
     * 优先用 LLM 填的 index（验证有效性），否则通过多维度匹配补全。
     */
    public List<SemanticOp> fixModifyOps(List<SemanticOp> modOps, List<ElementState> elements, String userText) {
        if (elements.isEmpty()) return modOps;

        // 方向词出现时 → 强制走方向匹配，无视 LLM 填的 targetIndex
        String dirWord = detectDirection(userText);
        boolean hasDirRef = dirWord != null;

        List<SemanticOp> fixed = new ArrayList<>();
        for (SemanticOp op : modOps) {
            if (hasDirRef) {
                List<Integer> matches = matchTargets(elements, userText);
                for (int mi : matches) {
                    fixed.add(copyWithIndex(op, mi));
                }
            } else if (op.targetIndex() != null) {
                int idx = op.targetIndex();
                if (idx >= 0 && idx < elements.size()) {
                    // 用户文本有明确筛选条件时才交叉验证，避免每次调 matchTargets 增加延迟
                    boolean hasCriteria = detectShape(userText) != null
                        || hasPositionKeyword(userText)
                        || hasColorKeyword(userText);
                    if (hasCriteria) {
                        List<Integer> matches = matchTargets(elements, userText);
                        if (!matches.isEmpty() && !matches.contains(idx)) {
                            for (int mi : matches) {
                                fixed.add(copyWithIndex(op, mi));
                            }
                            continue;
                        }
                    }
                    fixed.add(op);
                } else {
                    List<Integer> matches = matchTargets(elements, userText);
                    for (int mi : matches) {
                        fixed.add(copyWithIndex(op, mi));
                    }
                }
            } else {
                List<Integer> matches = matchTargets(elements, userText);
                for (int mi : matches) {
                    fixed.add(copyWithIndex(op, mi));
                }
            }
        }
        return fixed;
    }

    /**
     * 多维度匹配核心：形状(3分) + 位置(3分) + 颜色(2分) + 大小(1分)。
     * 返回匹配的元素索引列表，批量模式下返回所有达阈值的元素。
     */
    List<Integer> matchTargets(List<ElementState> elements, String userText) {
        if (elements.isEmpty()) return Collections.emptyList();

        // 提取形状条件（长关键词优先，避免"椭圆"误匹配"圆"）
        String shapeType = detectShape(userText);

        // 提取位置条件
        String posKey = null;
        for (String pk : POS_KEYS) {
            if (userText.contains(pk)) { posKey = pk; break; }
        }

        // 提取颜色条件
        String colorKey = null;
        for (String ck : COLOR_KEYS) {
            if (userText.contains(ck)) { colorKey = ck; break; }
        }

        // 提取大小条件
        String sizeKey = null;
        for (String sk : SIZE_KEYS) {
            if (userText.contains(sk)) { sizeKey = sk; break; }
        }

        // 指代词/批量 → 颜色和大小是修改目标值，不是搜索条件
        boolean hasDemo = userText.contains("这个") || userText.contains("那个") || userText.contains("它");
        boolean isBatch = false;
        for (String bk : BATCH_KEYS) {
            if (userText.contains(bk)) { isBatch = true; break; }
        }
        if (hasDemo || (isBatch && shapeType == null && posKey == null)) {
            colorKey = null;
            sizeKey = null;
        }

        // 指代词无其他条件 → 最后一个
        if (hasDemo && shapeType == null && posKey == null) {
            return List.of(elements.size() - 1);
        }

        // 批量无具体条件 → 全选
        if (isBatch && shapeType == null && posKey == null) {
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < elements.size(); i++) all.add(i);
            return all;
        }

        // 方向词检测：上/下/左/右（不含"左上"等组合）
        String dirWord = detectDirection(userText);
        if (dirWord != null) {
            return matchByDirection(elements, userText, dirWord, shapeType, colorKey, sizeKey, isBatch);
        }

        // 四维评分
        int bestScore = 0;
        int bestIdx = 0;
        int[] scores = new int[elements.size()];
        for (int i = 0; i < elements.size(); i++) {
            ElementState el = elements.get(i);
            int score = 0;
            if (shapeType != null && shapeType.equals(el.type())) score += 3;
            if (posKey != null && el.position() != null && containsPosition(el.position(), posKey)) score += 3;
            if (colorKey != null && colorKey.equals(el.colorName())) score += 2;
            if (sizeKey != null && sizeKey.equals(el.size())) score += 1;
            scores[i] = score;
            if (score > bestScore) { bestScore = score; bestIdx = i; }
        }

        if (bestScore == 0) return Collections.emptyList();

        if (isBatch) {
            int threshold = Math.max(bestScore - 1, 1);
            List<Integer> result = new ArrayList<>();
            for (int i = 0; i < scores.length; i++) {
                if (scores[i] >= threshold) result.add(i);
            }
            return result;
        }
        return List.of(bestIdx);
    }

    // ==================== 相对定位绘图 ====================

    /**
     * 处理带相对定位的 geometry 操作。
     * 检查 extra 中的 relativeTo/relativeDir，找到参考元素后用像素坐标生成 DrawingOp。
     */
    public List<DrawingOp> executeRelativeGeometry(List<SemanticOp> ops, List<ElementState> elements, String userText) {
        List<DrawingOp> result = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            SemanticOp op = ops.get(i);
            Map<String, Object> extra = op.extra();
            if (extra == null) continue;
            String relativeTo = (String) extra.get("relativeTo");
            String relativeDir = (String) extra.get("relativeDir");
            if (relativeTo == null || relativeDir == null) continue;

            ElementState ref = findRefElement(elements, relativeTo, userText);
            if (ref == null) continue;

            DrawingOp dOp = GeometryStrategy.createRelativeOp(op, ref.position(), relativeDir);
            result.add(dOp);
        }
        return result;
    }

    /** 根据参考名和用户文本找画布上的元素。多个同类元素时用多维度评分选最佳。 */
    private ElementState findRefElement(List<ElementState> elements, String name, String userText) {
        if (name == null || elements.isEmpty()) return null;
        if ("它".equals(name) || "这个".equals(name) || "那个".equals(name)) {
            return elements.get(elements.size() - 1);
        }
        String type = detectShape(name);
        if (type == null) return null;

        // 收集该类型的所有候选
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (type.equals(elements.get(i).type())) candidates.add(i);
        }
        if (candidates.isEmpty()) {
            // 找不到指定形状 → 兜底用最后一个
            return elements.get(elements.size() - 1);
        }
        if (candidates.size() == 1) return elements.get(candidates.get(0));

        // 多个同类元素 → 多维度评分，从 userText 提取筛选条件
        String posKey = null;
        for (String pk : POS_KEYS) {
            if (userText.contains(pk)) { posKey = pk; break; }
        }
        String colorKey = null;
        for (String ck : COLOR_KEYS) {
            if (userText.contains(ck)) { colorKey = ck; break; }
        }

        int bestScore = -1;
        int bestIdx = candidates.get(0);
        for (int idx : candidates) {
            ElementState el = elements.get(idx);
            int score = 0;
            if (posKey != null && el.position() != null && containsPosition(el.position(), posKey)) score += 3;
            if (colorKey != null && colorKey.equals(el.colorName())) score += 2;
            if (score > bestScore) { bestScore = score; bestIdx = idx; }
        }
        return elements.get(bestIdx);
    }

    // ==================== 后端相对定位安全网 ====================

    /**
     * 不依赖 LLM 的 extra 字段，直接检测用户文本中的相对定位关键词，
     * 用多维度评分找参考元素，强制重定位最后一个新增图形。
     * 作为 LLM 未输出 extra 时的兜底。
     */
    public boolean hasRelativePositioning(String userText) {
        return userText.contains("正上方") || userText.contains("正上面")
            || userText.contains("正下方") || userText.contains("正下面")
            || userText.contains("正左方") || userText.contains("正左面")
            || userText.contains("正右方") || userText.contains("正右面")
            || userText.contains("的左边") || userText.contains("的右边")
            || userText.contains("的上面") || userText.contains("的下面")
            || userText.contains("的上方") || userText.contains("的下方")
            || userText.contains("的左方") || userText.contains("的右方");
    }

    public List<DrawingOp> relocateNewOps(List<DrawingOp> ops, List<ElementState> elements, String userText) {
        if (elements.isEmpty()) return ops;

        String relDir = null;
        if (userText.contains("正上方") || userText.contains("正上面") || userText.contains("的上面") || userText.contains("的上方")) relDir = "above";
        else if (userText.contains("正下方") || userText.contains("正下面") || userText.contains("的下面") || userText.contains("的下方")) relDir = "below";
        else if (userText.contains("正左方") || userText.contains("正左面") || userText.contains("的左边") || userText.contains("的左方")) relDir = "left";
        else if (userText.contains("正右方") || userText.contains("正右面") || userText.contains("的右边") || userText.contains("的右方")) relDir = "right";
        if (relDir == null) return ops;

        // 多维度评分找参考元素
        List<Integer> matches = matchTargets(elements, userText);
        ElementState ref;
        if (matches.isEmpty()) {
            ref = elements.get(elements.size() - 1);
        } else {
            ref = elements.get(matches.get(0));
        }

        // 找最后一个新增图形（跳过 undo/clear/modify）
        int lastNewIdx = -1;
        for (int i = ops.size() - 1; i >= 0; i--) {
            DrawingOp op = ops.get(i);
            if (!"undo".equals(op.type()) && !"clear".equals(op.type()) && !"modify".equals(op.type())) {
                lastNewIdx = i;
                break;
            }
        }
        if (lastNewIdx < 0) return ops;

        // 重定位
        List<DrawingOp> result = new ArrayList<>();
        for (int i = 0; i < ops.size(); i++) {
            DrawingOp op = ops.get(i);
            if (i == lastNewIdx) {
                result.add(relocateOp(op, ref, relDir));
            } else {
                result.add(op);
            }
        }
        return result;
    }

    private DrawingOp relocateOp(DrawingOp op, ElementState ref, String dir) {
        int col = GeometryStrategy.posCol(ref.position());
        int row = GeometryStrategy.posRow(ref.position());

        if ("above".equals(dir))       row = Math.max(0, row - 1);
        else if ("below".equals(dir))  row = Math.min(2, row + 1);
        else if ("left".equals(dir))   col = Math.max(0, col - 1);
        else if ("right".equals(dir))  col = Math.min(2, col + 1);

        double x = col == 0 ? 200 : col == 2 ? 600 : 400;
        double y = row == 0 ? 150 : row == 2 ? 450 : 300;
        double halfW = op.width() / 2;
        double halfH = op.height() / 2;

        String type = op.type();
        double newX, newY;
        if ("rect".equals(type)) {
            newX = x - halfW;
            newY = y - halfH;
        } else if ("triangle".equals(type)) {
            newX = x;
            newY = y - halfH;
        } else {
            newX = x;
            newY = y;
        }

        return new DrawingOp(type, newX, newY, op.width(), op.height(),
            op.color(), op.fillColor(), op.strokeColor(), op.lineWidth(),
            op.text(), op.imageUrl(), op.targetIndex());
    }

    // ==================== 辅助方法 ====================

    /** 从文本检测形状，长关键词优先避免歧义 */
    private String detectShape(String text) {
        if (text.contains("椭圆")) return "ellipse";
        if (text.contains("三角")) return "triangle";
        if (text.contains("矩形") || text.contains("正方")) return "rect";
        if (text.contains("圆")) return "circle";
        if (text.contains("线")) return "line";
        return null;
    }

    /** 位置关键词匹配："左"能匹配"左上/左间/左下"，"左边"→"左" */
    private boolean containsPosition(String elementPos, String keyPos) {
        if (elementPos == null) return false;
        if (elementPos.contains(keyPos)) return true;
        String normalized = keyPos.replace("边","").replace("侧","").replace("方","").replace("面","");
        return elementPos.contains(normalized);
    }

    private String toPosition(double x, double y) {
        String h = x < 250 ? "左" : x > 550 ? "右" : "中";
        String v = y < 200 ? "上" : y > 400 ? "下" : "间";
        return h + v;
    }

    private String toSize(String type, double w, double h) {
        double area;
        if ("circle".equals(type)) {
            double r = w / 2;
            area = Math.PI * r * r;
        } else {
            area = w * h;
        }
        if (area < 5000) return "小";
        if (area > 30000) return "大";
        return "中";
    }

    private boolean hasPositionKeyword(String text) {
        for (String pk : POS_KEYS) {
            if (text.contains(pk)) return true;
        }
        return false;
    }

    private boolean hasColorKeyword(String text) {
        for (String ck : COLOR_KEYS) {
            if (text.contains(ck)) return true;
        }
        return false;
    }

    private String toColorName(String hex) {
        if (hex == null) return "黑";
        String name = HEX_TO_NAME.get(hex);
        return name != null ? name : hex;
    }

    private SemanticOp copyWithIndex(SemanticOp op, int idx) {
        return new SemanticOp(op.shape(), op.x(), op.y(), op.size(), op.color(),
            op.fillColor(), op.strokeColor(), op.fill(), op.stroke(),
            op.text(), op.extra(), idx);
    }

    // ==================== 方向匹配 ====================

    /** 检测方向词。参考对象必须在方向词之前出现，否则是位置描述。 */
    private String detectDirection(String text) {
        // 排除九宫格组合位置
        if (text.contains("左上") || text.contains("左下") || text.contains("右上") || text.contains("右下"))
            return null;
        // 排除相对定位关键词（走 executeRelativeGeometry / relocateNewOps）
        if (text.contains("正上方") || text.contains("正下方") || text.contains("正左方") || text.contains("正右方")
            || text.contains("正上面") || text.contains("正下面") || text.contains("正左面") || text.contains("正右面"))
            return null;

        // 找方向词位置
        String dirStr = null;
        int dirPos = -1;
        if (text.contains("右")) { dirStr = "right"; dirPos = text.indexOf("右"); }
        else if (text.contains("左")) { dirStr = "left"; dirPos = text.indexOf("左"); }
        else if (text.contains("上")) { dirStr = "up"; dirPos = text.indexOf("上"); }
        else if (text.contains("下")) { dirStr = "down"; dirPos = text.indexOf("下"); }
        if (dirStr == null) return null;

        // 参考对象必须在方向词前面："三角形左边的"→方向，"左边的三角形"→位置
        String before = text.substring(0, dirPos);
        boolean hasRefBefore = detectShape(before) != null
            || before.contains("它") || before.contains("这个") || before.contains("那个");
        return hasRefBefore ? dirStr : null;
    }

    /**
     * 方向过滤匹配。以参考元素为原点，同向所有元素参与评分。
     * 距离越近得分越高，确保"正方向"优先被选中。
     * 无批量词 → 评分最高；有批量词 → 该方向全部候选。
     */
    private List<Integer> matchByDirection(List<ElementState> elements, String userText,
                                            String dir, String shapeType, String colorKey,
                                            String sizeKey, boolean isBatch) {
        // 找参考元素：优先按用户提及的形状名，否则最后一个
        ElementState ref = null;
        if (shapeType != null) {
            for (int i = elements.size() - 1; i >= 0; i--) {
                if (shapeType.equals(elements.get(i).type())) { ref = elements.get(i); break; }
            }
        }
        if (ref == null) ref = elements.get(elements.size() - 1);

        int refCol = GeometryStrategy.posCol(ref.position());
        int refRow = GeometryStrategy.posRow(ref.position());

        // 无批量词 → 只看同行/同列（正/最方向）；有批量词 → 该方向全列/全行
        // 按方向筛选候选
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < elements.size(); i++) {
            if (i == ref.index()) continue;
            ElementState el = elements.get(i);
            int elCol = GeometryStrategy.posCol(el.position());
            int elRow = GeometryStrategy.posRow(el.position());

            if ("right".equals(dir) && elCol > refCol) {
                if (isBatch || elRow == refRow) candidates.add(i);
            } else if ("left".equals(dir) && elCol < refCol) {
                if (isBatch || elRow == refRow) candidates.add(i);
            } else if ("down".equals(dir) && elRow > refRow) {
                if (isBatch || elCol == refCol) candidates.add(i);
            } else if ("up".equals(dir) && elRow < refRow) {
                if (isBatch || elCol == refCol) candidates.add(i);
            }
        }
        if (candidates.isEmpty()) return Collections.emptyList();

        if (isBatch) return candidates;

        // 非批量：距离加权评分选最佳
        int bestScore = -1;
        int bestIdx = candidates.get(0);
        for (int ci : candidates) {
            ElementState el = elements.get(ci);
            int elCol = GeometryStrategy.posCol(el.position());
            int elRow = GeometryStrategy.posRow(el.position());
            int colDiff = Math.abs(elCol - refCol);
            int rowDiff = Math.abs(elRow - refRow);

            int score = (3 - colDiff) + (2 - rowDiff);
            if ("right".equals(dir) || "left".equals(dir)) {
                if (elRow == refRow) score += 2;
            } else {
                if (elCol == refCol) score += 2;
            }
            if (colorKey != null && colorKey.equals(el.colorName())) score += 2;
            if (sizeKey != null && sizeKey.equals(el.size())) score += 1;
            if (score > bestScore) { bestScore = score; bestIdx = ci; }
        }
        return List.of(bestIdx);
    }
}
