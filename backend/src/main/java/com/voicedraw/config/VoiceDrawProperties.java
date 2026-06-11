package com.voicedraw.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "voice-draw.asr")
@Data
public class VoiceDrawProperties {

    private String model = "paraformer-v1";
    private int sampleRate = 16000;
    private String format = "wav";
    private String language = "zh";

}
