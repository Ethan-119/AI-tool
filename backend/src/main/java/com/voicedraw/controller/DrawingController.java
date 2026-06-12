package com.voicedraw.controller;

import com.google.gson.Gson;
import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.DrawingSession;
import com.voicedraw.model.IntentResult;
import com.voicedraw.service.AsrService;
import com.voicedraw.service.CommandParserService;
import com.voicedraw.service.DrawingService;
import com.voicedraw.service.strategy.IntentStrategy;
import com.voicedraw.service.strategy.IntentStrategyFactory;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/draw")
public class DrawingController {

    private final AsrService asrService;
    private final CommandParserService parserService;
    private final DrawingService drawingService;
    private final IntentStrategyFactory strategyFactory;
    private final Gson gson = new Gson();

    public DrawingController(AsrService asrService, CommandParserService parserService,
                              DrawingService drawingService, IntentStrategyFactory strategyFactory) {
        this.asrService = asrService;
        this.parserService = parserService;
        this.drawingService = drawingService;
        this.strategyFactory = strategyFactory;
    }

    @PostMapping(value = "/voice", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter processVoice(@RequestParam("audio") MultipartFile audio,
                                   @RequestParam(required = false) String sessionId) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时（通义万相可能慢）

        new Thread(() -> {
            try {
                // 1. ASR 转写（直接传字节数组，零落盘）
                byte[] audioData = audio.getBytes();
                String text = asrService.transcribe(audioData);
                send(emitter, "text", Map.of("text", text));

                if (text == null || text.isBlank()) {
                    send(emitter, "error", Map.of("message", "未识别到语音内容，请重试"));
                    emitter.complete();
                    return;
                }

                // 3. LLM 解析意图
                IntentResult intent = parserService.parse(text);
                DrawingSession session = resolveSession(sessionId);
                int stepNum = session.getStepCount() + 1;

                // 永久保存记录
                drawingService.saveRecord(session.getSessionId(), stepNum, text, intent);

                if (!intent.understood()) {
                    send(emitter, "clarification", Map.of("text", intent.clarification()));
                    emitter.complete();
                    return;
                }

                // 4. 策略执行
                IntentStrategy strategy = strategyFactory.get(intent.type());
                List<DrawingOp> ops = strategy.execute(intent.operations());

                // 5. 逐条推送绘图指令
                for (DrawingOp op : ops) {
                    send(emitter, "command", op);
                }
                send(emitter, "done", Map.of("sessionId", session.getSessionId(), "step", stepNum));

            } catch (Exception e) {
                send(emitter, "error", Map.of("message", "处理失败: " + e.getMessage()));
            } finally {
                emitter.complete();
            }
        }).start();

        return emitter;
    }

    private DrawingSession resolveSession(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return drawingService.createSession();
        }
        DrawingSession s = drawingService.getSession(sessionId);
        return s != null ? s : drawingService.createSession();
    }

    private void send(SseEmitter emitter, String event, Object data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(gson.toJson(data)));
        } catch (Exception ignored) {
            // SSE 连接已断开
        }
    }
}
