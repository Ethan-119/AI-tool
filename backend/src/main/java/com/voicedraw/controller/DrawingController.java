package com.voicedraw.controller;

import com.google.gson.Gson;
import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.DrawingSession;
import com.voicedraw.model.ElementState;
import com.voicedraw.model.IntentResult;
import com.voicedraw.model.SemanticOp;
import com.voicedraw.service.AsrService;
import com.voicedraw.service.CanvasStateService;
import com.voicedraw.service.CommandParserService;
import com.voicedraw.service.DrawingService;
import com.voicedraw.service.strategy.IntentStrategy;
import com.voicedraw.service.strategy.IntentStrategyFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/draw")
public class DrawingController {

    private final AsrService asrService;
    private final CommandParserService parserService;
    private final DrawingService drawingService;
    private final IntentStrategyFactory strategyFactory;
    private final CanvasStateService canvasStateService;
    private final Gson gson = new Gson();

    public DrawingController(AsrService asrService, CommandParserService parserService,
                              DrawingService drawingService, IntentStrategyFactory strategyFactory,
                              CanvasStateService canvasStateService) {
        this.asrService = asrService;
        this.parserService = parserService;
        this.drawingService = drawingService;
        this.strategyFactory = strategyFactory;
        this.canvasStateService = canvasStateService;
    }

    @PostMapping(value = "/voice", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processVoice(@RequestParam("audio") MultipartFile audio,
                                   @RequestParam(required = false) String sessionId,
                                   @RequestParam(required = false) String canvasState) {
        SseEmitter emitter = new SseEmitter(300_000L);

        new Thread(() -> {
            try {
                byte[] audioData = audio.getBytes();
                String text = asrService.transcribe(audioData);
                send(emitter, "text", Map.of("text", text));

                if (text == null || text.isBlank()) {
                    send(emitter, "error", Map.of("message", "未识别到语音内容，请重试"));
                    emitter.complete();
                    return;
                }

                // 1. 获取结构化画布状态，格式化为 LLM 上下文
                List<ElementState> elements = canvasStateService.getElements(sessionId);
                String context = canvasStateService.formatContext(elements);
                if (context == null && canvasState != null && !canvasState.isBlank()) {
                    context = canvasState; // 首次请求兜底：用前端传来的字符串
                }
                Integer lastOp = canvasStateService.getLastOpIndex(sessionId);
                if (lastOp != null && lastOp >= 0 && context != null) {
                    context += "最近操作：[${lastOp}]（\"它/他/这个/那个\"默认指它）".replace("${lastOp}", String.valueOf(lastOp));
                }
                System.out.println("[DEBUG] Canvas context for LLM:\n" + (context != null ? context : "(empty)"));
                System.out.println("[DEBUG] User text: " + text);

                // 2. LLM 解析（单次调用，复合指令由 LLM 内部拆分）
                IntentResult intent = parserService.parse(text, context);

                if (!intent.understood() || intent.operations() == null) {
                    send(emitter, "clarification", Map.of("text",
                        intent.clarification() != null ? intent.clarification() : "未能理解您的指令，请换个说法"));
                    emitter.complete();
                    return;
                }

                // 2.5 L3 类型（image / style_transfer）直接分派到对应策略
                if ("image".equals(intent.type()) || "style_transfer".equals(intent.type())) {
                    DrawingSession session = resolveSession(sessionId);
                    int stepNum = session.getStepCount() + 1;
                    drawingService.saveRecord(session.getSessionId(), stepNum, text, intent);

                    send(emitter, "progress", Map.of("message", "正在生成图像..."));
                    IntentStrategy s = strategyFactory.get(intent.type());
                    List<DrawingOp> ops = s.execute(intent.operations());
                    for (DrawingOp op : ops) {
                        send(emitter, "command", op);
                    }
                    send(emitter, "done", Map.of("sessionId", session.getSessionId(), "step", stepNum));

                    List<ElementState> updated = canvasStateService.applyOps(elements, ops);
                    canvasStateService.saveElements(session.getSessionId(), updated);
                    canvasStateService.saveLastOpIndex(session.getSessionId(), updated.isEmpty() ? -1 : updated.size() - 1);
                    emitter.complete();
                    return;
                }

                // 3. 分离 geometry / 相对定位geometry / modify 操作
                List<SemanticOp> geomOps = new ArrayList<>();
                List<SemanticOp> relativeGeomOps = new ArrayList<>();
                List<SemanticOp> modOps = new ArrayList<>();
                for (SemanticOp op : intent.operations()) {
                    if ("modify".equals(op.shape())) {
                        modOps.add(op);
                    } else if (op.extra() != null && op.extra().containsKey("relativeTo")) {
                        relativeGeomOps.add(op);
                    } else {
                        geomOps.add(op);
                    }
                }

                // 4. 多维度匹配修正 modify 的 targetIndex
                List<SemanticOp> fixedModOps = canvasStateService.fixModifyOps(modOps, elements, text);

                DrawingSession session = resolveSession(sessionId);
                int stepNum = session.getStepCount() + 1;
                drawingService.saveRecord(session.getSessionId(), stepNum, text, intent);

                // 5. 分别执行策略：普通geometry → 相对定位geometry → modify
                List<DrawingOp> ops = new ArrayList<>();
                if (!geomOps.isEmpty()) {
                    IntentStrategy s = strategyFactory.get("geometry");
                    ops.addAll(s.execute(geomOps));
                }
                if (!relativeGeomOps.isEmpty()) {
                    ops.addAll(canvasStateService.executeRelativeGeometry(relativeGeomOps, elements, text));
                }
                if (!fixedModOps.isEmpty()) {
                    IntentStrategy s = strategyFactory.get("modify");
                    ops.addAll(s.execute(fixedModOps));
                }

                // 6. 后端安全网：LLM 没输出 extra 时，检测用户文本直接做相对定位
                if (relativeGeomOps.isEmpty() && canvasStateService.hasRelativePositioning(text)) {
                    ops = canvasStateService.relocateNewOps(ops, elements, text);
                }

                for (int i = 0; i < ops.size(); i++) {
                    send(emitter, "command", ops.get(i));
                }
                send(emitter, "done", Map.of("sessionId", session.getSessionId(), "step", stepNum));

                // 7. 更新 Redis 结构化状态 + 记录最近操作
                List<ElementState> updated = canvasStateService.applyOps(elements, ops);
                canvasStateService.saveElements(session.getSessionId(), updated);
                int lastOpIdx = !fixedModOps.isEmpty() ? fixedModOps.get(fixedModOps.size() - 1).targetIndex()
                    : !updated.isEmpty() ? updated.size() - 1 : -1;
                canvasStateService.saveLastOpIndex(session.getSessionId(), lastOpIdx);

            } catch (Exception e) {
                send(emitter, "error", Map.of("message", "处理失败: " + e.getMessage()));
            } finally {
                emitter.complete();
            }
        }).start();

        return emitter;
    }

    private DrawingSession resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) return drawingService.createSession();
        DrawingSession s = drawingService.getSession(sessionId);
        return s != null ? s : drawingService.createSession();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(gson.toJson(data)));
        } catch (Exception ignored) {}
    }
}
