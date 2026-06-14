# VoiceDraw — 语音绘图

语音控制画布。用户说话 → LLM 解析意图 → 策略执行 → Canvas 渲染。

---
## 视频：https://www.bilibili.com/video/BV1etJA6JEBn/?vd_source=842750e3547dc6419312dc37e707a5dc

其中 L1 最简单 半天基本完成， L2 难度最大，一天半完成，但效果有限， L3 难度其次，关键是L1和L2 的设计，影响L3。
## 策略模式

LLM 不直接操作画布，只输出 JSON 描述"用户想干什么"。后端根据意图类型分派到对应策略：

```
用户语音: "在左上画红色三角，然后把三角改成蓝色"
         │
         ▼
    LLM 解析意图
         │
    {"type":"geometry","operations":[
      {"shape":"triangle","x":"left","y":"top","color":"红"},   ← 意图: 画图形
      {"shape":"modify","targetIndex":0,"color":"蓝"}            ← 意图: 改颜色
    ]}
         │
         ▼
    IntentStrategyFactory 分派
         │
    ┌────┴────┬──────────┬──────────┬──────────┐
    ▼         ▼          ▼          ▼          ▼
  Geometry  Modify    ImageGen  Template  StyleTransfer
  Strategy  Strategy  Strategy  Strategy   Strategy
  (画图形)  (改属性)  (AI生图)  (模板⚠)    (风格迁移⚠)
```

**核心原则**：LLM 输出语义标签（`left/center/right`、`top/middle/bottom`、`small/medium/large`），策略层负责映射为像素。LLM 不关心画布多大、像素多少——那都是策略层的事。

| 意图 type | 策略 | LLM 输出什么 | 策略做什么 |
|-----------|------|-------------|-----------|
| `geometry` | GeometryStrategy | shape/x/y/size/color 语义标签 | 语义→像素映射，生成 DrawingOp |
| `modify` | ModifyStrategy | targetIndex + 新颜色/填充/描边 | 生成修改 DrawingOp，前端更新元素 |
| `image` | ImageGenStrategy | text/x/y/size + extra{style, mood, colorPalette, composition} | 语义→高质量 prompt，调通义万相生图 |
| `template` | TemplateStrategy | 模板名（房子/太阳/树） | 拆解为子图形，递归走 GeometryStrategy（⚠ 未接通：Controller 未路由） |
| `style_transfer` | StyleTransferStrategy | extra{style} | 风格语义→prompt，调万相生成风格化图（⚠ 未完成：缺画布截图→万相图生图通路） |

---

## L1 语义坐标映射（九宫格棋盘）

LLM 只输出 `x: "left"` `y: "top"` 这样的标签，GeometryStrategy 负责映射为像素。画布 800×600：

```
        left(200)   center(400)  right(600)
        ┌──────────┬──────────┬──────────┐
top(150)│  1 左上   │  2 中上   │  3 右上   │
        ├──────────┼──────────┼──────────┤
mid(300)│  4 左间   │  5 中间   │  6 右间   │
        ├──────────┼──────────┼──────────┤
bot(450)│  7 左下   │  8 中下   │  9 右下   │
        └──────────┴──────────┴──────────┘
```

尺寸映射：`small→60px` `medium→100px` `large→150px`。12 种基础色名映射 hex。

---

## L2 上下文理解

L2 在 L1 单指令基础上增加复合、指代、批量、相对定位等上下文能力。

### 复合指令

用户一句话包含多个操作，LLM 拆解为独立 operation，后端并行解析再合并。

| 示例 | LLM 输出 |
|------|----------|
| "在左上画红色三角，在右下画绿色椭圆" | `operations:[{shape:triangle,x:left,y:top,color:红},{shape:ellipse,x:right,y:bottom,color:绿}]` |
| "画圆然后把它改成蓝色" | `operations:[{shape:circle,...},{shape:modify,targetIndex:0,color:蓝}]` |

### 指代引用 + 最近操作追踪

"它/这个/那个" 通过 Redis key `voice:lastop:` 追踪最近被操作图形的 index，注入 LLM 上下文。LLM 据此解析指代，不再盲目默认最后一个。

```
上下文示例:
[0] circle 位置=左上 大小=中 颜色=红
[1] rect 位置=右下 大小=大 颜色=蓝
最近操作：[1]（"它/他/这个/那个"默认指它）
```

### 填充与描边

modify 类型独立修改 `fillColor` / `strokeColor`，不改其他属性。

| 示例 | 效果 |
|------|------|
| "填充红色" | 最近操作图形 fillColor=红，shape 不变 |
| "描边加粗" | strokeColor 设为当前颜色 |
| "把三角改成蓝色" | color + fillColor + strokeColor 同步更新 |

### 批量修改

批量关键词（都/全部/所有）触发全量匹配，无筛选条件=全选，有筛选条件按维度精确命中。

| 示例 | 匹配逻辑 |
|------|----------|
| "把左边的都改成红色" | 位置=左的所有图形 |
| "把所有三角改成蓝色" | type=triangle 全部 |
| "把大的改成小" | size=large 全部 |

### 相对定位

方向描述（正上方/正下方/正左方/正右方）→ extra{relativeTo, relativeDir}，基于参照物位置按棋盘行/列偏移。

| 示例 | LLM 输出 |
|------|----------|
| "在三角形上方画红色矩形" | `{shape:rect,extra:{relativeTo:"三角",relativeDir:"above"}}` |
| "把三角形右边的图形改成蓝色" | modify + 方向匹配找到右侧图形 |

**防混淆规则**：要画的形状以用户说的为准，不受参照物影响（"在三角上方画矩形"→新图形是 rect 不是 triangle）。

### 形状纠偏（fixShapes）

LLM 偶尔混淆参照物与目标形状。`fixShapes()` 按用户文本中形状关键词的**出现顺序**逐个匹配，纠正 LLM 输出的错位 shape。单绘制操作取文本最后一个形状，避免参照物干扰。

### 多维度匹配

修改图形时，从 Redis 结构化缓存中找目标。四个维度加权评分：

| 维度 | 权重 | 说明 |
|------|------|------|
| 形状 | 3 | 用户提了"三角" → element.type="triangle" +3 |
| 位置 | 3 | 用户提了"左边" → element.position 含"左" +3 |
| 颜色 | 2 | 用户提了"红色" → element.colorName="红" +2 |
| 大小 | 1 | 用户提了"大" → element.size="大" +1 |

匹配策略：
- 方向词出现 → 强制方向匹配，无视 LLM 的 targetIndex
- LLM 有 targetIndex 且有筛选条件 → 交叉验证，不一致用后端结果
- 指代/批量 → 颜色和大小从搜索条件剥离（它们是修改目标值不是筛选条件）

### 三层兜底

```
第一层: 单次 LLM 调用 → 解析 JSON
  失败 ↓
第二层: 拆句（逗号/句号/然后/接着）→ 分别调 LLM → 合并
  失败 ↓
第三层: 关键词匹配（detectShape/detectColor/detectX/detectY）
```

## L3 创意生图与风格迁移

L3 在 L1/L2 的几何图形基础上扩展了图像级能力。LLM 输出语义标签（风格、氛围、色调、构图），后端查映射表拼装高质量 prompt，调通义万相生成图像。

> **设计反思**：L2 的九宫格棋盘是为小尺寸几何图形（60~150px）设计的，对 L3 图像（300~700px）来说粒度太粗。L3 图像 300~700px 塞进同一个棋盘时，位置和空间表达能力受限。后续思路：在保证 L1/L2 基础功能不受影响的前提下，L3 的语音→位置映射不再局限九宫格，升级为像素级定位。

### ImageGenStrategy — 创意生图

用户说 "在中间画一只水彩风格的猫" → LLM 输出：

```json
{"type":"image","operations":[{"text":"一只猫","x":"center","y":"middle","extra":{"style":"水彩"}}]}
```

后端四组语义→prompt 映射表：

| 维度 | 示例关键词 | 映射为 |
|------|-----------|--------|
| 风格 (STYLE) | 水彩/油画/素描/水墨/赛博朋克/像素/卡通/写实/扁平/儿童画/浮世绘/极简 | 英文艺术风格描述 |
| 氛围 (MOOD) | 温暖/冷静/欢快/忧郁/浪漫/神秘/活泼/宁静 | 氛围提示词 |
| 色调 (PALETTE) | 暖色系/冷色系/莫兰迪/高饱和/黑白/粉彩/大地色/金属色 | 色系提示词 |
| 构图 (COMPOSITION) | 居中/三分法/对角线/散点 | 构图提示词 |

位置/大小仍走九宫格映射（与 L1/L2 统一），图像尺寸 300~700px。

### StyleTransferStrategy — 风格迁移 ⚠ 未完成

用户说 "把画布变成素描风格" → LLM 输出：

```json
{"type":"style_transfer","operations":[{"extra":{"style":"素描"}}]}
```

**当前状态**：后端复用风格映射表生成 prompt，但仅做文生图（text-to-image），没有拿到画布截图做真正的图生图风格迁移。

**欠缺**：画布截图→万相图生图通路。前端 `getSnapshot()` 已预留，策略骨架已建，待后续接通。

### TemplateStrategy — 模板组合 ⚠ 未接通

模板（房子/太阳/树）拆解逻辑已实现并注册工厂，但 `DrawingController` 未路由 `template` 类型，实际走不到该策略。

### L3 条件注入

`CommandParserService` 仅在用户提到风格/画布/绘画类关键词时才追加 L3 格式说明，避免 prompt 膨胀影响 L1/L2 精度。

---

## 架构总览

```
前端录音(WAV) → POST /api/draw/voice
                    │
                    ▼
              ASR 听写（DashScope）
                    │
                    ▼
         LLM 意图解析（qwen3.7-plus）
         ← Redis 缓存的结构化画布状态
                    │
                    ▼
          IntentStrategyFactory 分派
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
   Geometry    Modify      Image/Template
   Strategy    Strategy   /StyleTransfer
        │           │           │
        └───────────┼───────────┘
                    ▼
            SSE 推流 DrawingOp
                    │
                    ▼
         前端 Canvas 渲染 + 状态回写 Redis
```

## 技术栈

Vue 3 + Canvas API / Spring Boot 3 + MyBatis-Plus + Redis / DashScope ASR + qwen3.7-plus + 通义万相

## 项目结构

```
frontend/src/components/
  VoiceButton.vue      # 按住录音松开发送
  DrawingCanvas.vue    # Canvas 渲染 + 元素摘要生成

backend/src/main/java/com/voicedraw/
  controller/DrawingController.java    # SSE 接口
  service/
    CommandParserService.java          # LLM 提示词 + 三层兜底 + L3条件注入 + shape纠偏
    CanvasStateService.java            # 结构化状态 + 多维度匹配 + 方向匹配 + 最近操作追踪
    AsrService.java                    # DashScope 语音识别
    DrawingService.java                # 会话管理 MySQL 持久化
    strategy/
      IntentStrategyFactory.java       # 意图→策略分派
      GeometryStrategy.java            # 语义→像素 + 九宫格行列偏移
      ModifyStrategy.java              # 修改操作
      ImageGenStrategy.java            # AI 生图（L3 语义→prompt）
      StyleTransferStrategy.java       # 风格迁移（L3 ⚠ 未完成）
      TemplateStrategy.java            # 模板递归拆解（⚠ 未接通）
  model/
    IntentResult.java    # LLM 返回 (understood + type + operations)
    SemanticOp.java      # 语义操作 (shape/x/y/size/color/extra)
    DrawingOp.java       # 像素指令 (x/y/width/height/fillColor)
    ElementState.java    # 画布元素缓存 (position/size/colorName)
```
