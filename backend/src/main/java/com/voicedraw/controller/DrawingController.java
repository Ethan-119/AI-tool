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
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/draw")
public class DrawingController {

    private final AsrService asrService;
    private final CommandParserService parserService;
    private final DrawingService drawingService;
    private final IntentStrategyFactory strategyFactory;
    private final RedisTemplate<String, Object> redisTemplate;
    private final Gson gson = new Gson();

    public DrawingController(AsrService asrService, CommandParserService parserService,
                              DrawingService drawingService, IntentStrategyFactory strategyFactory,
                              RedisTemplate<String, Object> redisTemplate) {
        this.asrService = asrService;
        this.parserService = parserService;
        this.drawingService = drawingService;
        this.strategyFactory = strategyFactory;
        this.redisTemplate = redisTemplate;
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

                // 注入画布上下文
                String context = getCanvasContext(sessionId, canvasState);
                IntentResult intent = parserService.parse(text, context);
                DrawingSession session = resolveSession(sessionId);
                int stepNum = session.getStepCount() + 1;

                drawingService.saveRecord(session.getSessionId(), stepNum, text, intent);

                if (!intent.understood()) {
                    send(emitter, "clarification", Map.of("text", intent.clarification()));
                    emitter.complete();
                    return;
                }

                IntentStrategy strategy = strategyFactory.get(intent.type());
                List<DrawingOp> ops = strategy.execute(intent.operations());

                for (DrawingOp op : ops) {
                    send(emitter, "command", op);
                }
                send(emitter, "done", Map.of("sessionId", session.getSessionId(), "step", stepNum));

                // 更新 Redis 画布快照
                saveCanvasState(session.getSessionId(), canvasState, ops);

            } catch (Exception e) {
                send(emitter, "error", Map.of("message", "处理失败: " + e.getMessage()));
            } finally {
                emitter.complete();
            }
        }).start();

        return emitter;
    }

    private String getCanvasContext(String sessionId, String state) {
        if (state != null && !state.isBlank()) return state;
        if (sessionId != null) {
            Object cached = redisTemplate.opsForValue().get("voice:canvas:" + sessionId);
            return cached != null ? cached.toString() : null;
        }
        return null;
    }

    private void saveCanvasState(String sessionId, String state, List<DrawingOp> ops) {
        // 根据 operations 更新画布状态快照，简化为直接存前端传来的 state
        if (state != null && !state.isBlank()) {
            redisTemplate.opsForValue().set("voice:canvas:" + sessionId, state, 30, TimeUnit.MINUTES);
        }
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
