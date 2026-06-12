package com.voicedraw.service.strategy;

import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class IntentStrategyFactory {

    private final Map<String, IntentStrategy> strategies;

    public IntentStrategyFactory(GeometryStrategy gs, TemplateStrategy ts, ImageGenStrategy is) {
        strategies = Map.of(gs.type(), gs, ts.type(), ts, is.type(), is);
    }

    public IntentStrategy get(String type) {
        IntentStrategy s = strategies.get(type);
        return s != null ? s : strategies.get("geometry");  // 默认兜底 geometry
    }
}
