package com.voicedraw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice-draw")
@Data
public class VoiceDrawProperties {

    private String apiKey;

    private Llm llm = new Llm();
    private Image image = new Image();
    private Asr asr = new Asr();

    @Data
    public static class Llm {
        private String model = "qwen3-max";
        private double temperature = 0.1;
    }

    @Data
    public static class Image {
        private String model = "wanx-v1";
        private String size = "1024*1024";
    }

    @Data
    public static class Asr {
        private String model = "paraformer-realtime-v1";
        private int sampleRate = 16000;
        private String format = "wav";
        private String language = "zh";
    }
}
