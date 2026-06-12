package com.voicedraw.service;

import com.alibaba.dashscope.audio.asr.recognition.Recognition;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionParam;
import com.alibaba.dashscope.audio.asr.recognition.RecognitionResult;
import com.voicedraw.config.VoiceDrawProperties;
import io.reactivex.Flowable;
import org.springframework.stereotype.Service;

import java.nio.ByteBuffer;

/**
 * 语音转文字。使用 DashScope Recognition 流式 API，
 * 直接传入音频字节数组，零落盘。
 */
@Service
public class AsrService {

    private final VoiceDrawProperties props;

    public AsrService(VoiceDrawProperties props) { this.props = props; }

    public String transcribe(byte[] audioData) {
        if (audioData == null || audioData.length == 0) return null;
        try {
            RecognitionParam param = RecognitionParam.builder()
                .apiKey(props.getApiKey())
                .model(props.getAsr().getModel())
                .format(props.getAsr().getFormat())
                .sampleRate(props.getAsr().getSampleRate())
                .build();
            Flowable<ByteBuffer> audioFlow = Flowable.just(ByteBuffer.wrap(audioData));
            Flowable<RecognitionResult> results = new Recognition().streamCall(param, audioFlow);
            StringBuilder sb = new StringBuilder();
            results.blockingForEach(r -> {
                if (r.getSentence() != null && r.getSentence().getText() != null)
                    sb.append(r.getSentence().getText());
            });
            return sb.isEmpty() ? null : sb.toString();
        } catch (Exception ignored) {}
        return null;
    }
}
