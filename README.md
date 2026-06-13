# VoiceDraw — 语音绘图

语音控制画布。用户说话 → LLM 解析意图 → 策略执行 → Canvas 渲染。

---

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
    ┌────┴────┬──────────┬──────────┐
    ▼         ▼          ▼          ▼
  Geometry  Modify    ImageGen   Template
  Strategy  Strategy  Strategy   Strategy
  (画图形)  (改属性)   (AI生图)   (模板拆解)
```

**核心原则**：LLM 输出语义标签（`left/center/right`、`top/middle/bottom`、`small/medium/large`），策略层负责映射为像素。LLM 不关心画布多大、像素多少——那都是策略层的事。

| 意图 type | 策略 | LLM 输出什么 | 策略做什么 |
|-----------|------|-------------|-----------|
| `geometry` | GeometryStrategy | shape/x/y/size/color 语义标签 | 语义→像素映射，生成 DrawingOp |
| `modify` | ModifyStrategy | targetIndex + 新颜色/填充/描边 | 生成修改 DrawingOp，前端更新元素 |
| `image` | ImageGenStrategy | 抽象概念（猫/狗/花） | 调通义万相生成图片 |
| `template` | TemplateStrategy | 模板名（房子/太阳/树） | 拆解为子图形，递归走 GeometryStrategy |

---

## 语义坐标映射（L1/L2 九宫格棋盘）

LLM 输出语义坐标，GeometryStrategy 映射为像素。画布 800×600：

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

LLM 只输出 `x: "left"` `y: "top"` 这样的标签，GeometryStrategy 负责 `"left"→200` `"top"→150`。

相对定位也基于此棋盘：
- **画图形** "在4的正下方画" → row+1 → 7 号位
- **改图形** "把4右边的改成红" → 以4为原点，同列 col>0 的候选参与评分

> L1/L2 图形尺寸可控（60~150px），九宫格装得下。L3 AI 生图是 512×512 像素级大图，相对定位需要切到像素偏移路径——基础设施已预留（`ElementState` 存有 width/height，`createRelativeOp` 可改签名），后续单独处理。

---

## 多维度匹配

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
- 三层兜底：单次 LLM → 拆句分别调 LLM → 关键词匹配

---

## 架构总览

```
前端录音(WAV) → POST /api/draw/voice
                    │
                    ▼
              ASR 听写（DashScope）
                    │
                    ▼
         LLM 意图解析（qwen3-max）
         ← Redis 缓存的结构化画布状态
                    │
                    ▼
          IntentStrategyFactory 分派
                    │
        ┌───────────┼───────────┐
        ▼           ▼           ▼
   Geometry    Modify      Image/Template
   Strategy    Strategy     Strategy
        │           │           │
        └───────────┼───────────┘
                    ▼
            SSE 推流 DrawingOp
                    │
                    ▼
         前端 Canvas 渲染 + 状态回写 Redis
```

## 技术栈

Vue 3 + Canvas API / Spring Boot 3 + MyBatis-Plus + Redis / DashScope ASR + qwen3-max + 通义万相

## 项目结构

```
frontend/src/components/
  VoiceButton.vue      # 按住录音松开发送
  DrawingCanvas.vue    # Canvas 渲染 + 元素摘要生成

backend/src/main/java/com/voicedraw/
  controller/DrawingController.java    # SSE 接口
  service/
    CommandParserService.java          # LLM 提示词 + 三层兜底
    CanvasStateService.java            # 结构化状态 + 多维度匹配 + 方向匹配
    AsrService.java                    # DashScope 语音识别
    DrawingService.java                # 会话管理 MySQL 持久化
    strategy/
      IntentStrategyFactory.java       # 意图→策略分派
      GeometryStrategy.java            # 语义→像素 + 九宫格行列偏移
      ModifyStrategy.java              # 修改操作
      ImageGenStrategy.java            # AI 生图
      TemplateStrategy.java            # 模板递归拆解
  model/
    IntentResult.java    # LLM 返回 (understood + type + operations)
    SemanticOp.java      # 语义操作 (shape/x/y/size/color/extra)
    DrawingOp.java       # 像素指令 (x/y/width/height/fillColor)
    ElementState.java    # 画布元素缓存 (position/size/colorName)
```
