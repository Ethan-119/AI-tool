package com.voicedraw.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.google.gson.Gson;
import com.voicedraw.mapper.DrawingRecordMapper;
import com.voicedraw.mapper.DrawingSessionMapper;
import com.voicedraw.model.DrawingRecord;
import com.voicedraw.model.DrawingSession;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

@Service
public class DrawingService {

    private final DrawingSessionMapper sessionMapper;
    private final DrawingRecordMapper recordMapper;
    private final Gson gson = new Gson();

    public DrawingService(DrawingSessionMapper sessionMapper, DrawingRecordMapper recordMapper) {
        this.sessionMapper = sessionMapper;
        this.recordMapper = recordMapper;
    }

    /**
     * 创建新会话。
     */
    public DrawingSession createSession() {
        DrawingSession s = new DrawingSession();
        s.setSessionId(UUID.randomUUID().toString().replace("-", ""));
        s.setTitle("未命名画作");
        s.setStepCount(0);
        s.setStatus("active");
        s.setCreateTime(LocalDateTime.now());
        s.setUpdateTime(LocalDateTime.now());
        sessionMapper.insert(s);
        return s;
    }

    /**
     * 根据 session_id 查询会话。
     */
    public DrawingSession getSession(String sessionId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<DrawingSession>()
            .eq(DrawingSession::getSessionId, sessionId));
    }

    /**
     * 保存一轮绘图记录。
     */
    public DrawingRecord saveRecord(String sessionId, int stepNum,
                                     String originalText, Object command) {
        DrawingRecord r = new DrawingRecord();
        r.setSessionId(sessionId);
        r.setStepNum(stepNum);
        r.setOriginalText(originalText);
        r.setCommandJson(gson.toJson(command));
        r.setIsUndone(0);
        r.setCreateTime(LocalDateTime.now());
        recordMapper.insert(r);

        // 更新会话步数
        DrawingSession s = getSession(sessionId);
        if (s != null) {
            s.setStepCount(stepNum);
            s.setUpdateTime(LocalDateTime.now());
            sessionMapper.updateById(s);
        }
        return r;
    }

    /**
     * 撤销一步。
     */
    public boolean undo(String sessionId, int stepNum) {
        DrawingRecord r = recordMapper.selectOne(new LambdaQueryWrapper<DrawingRecord>()
            .eq(DrawingRecord::getSessionId, sessionId)
            .eq(DrawingRecord::getStepNum, stepNum));
        if (r != null) {
            r.setIsUndone(1);
            recordMapper.updateById(r);
            return true;
        }
        return false;
    }
}
