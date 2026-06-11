package com.voicedraw;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
@MapperScan("com.voicedraw.mapper")
public class VoiceDrawApplication {
    public static void main(String[] args) {
        SpringApplication.run(VoiceDrawApplication.class, args);
    }
}
