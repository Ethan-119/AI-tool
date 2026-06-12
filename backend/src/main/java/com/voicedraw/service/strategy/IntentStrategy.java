package com.voicedraw.service.strategy;

import com.voicedraw.model.DrawingOp;
import com.voicedraw.model.SemanticOp;

import java.util.List;

public interface IntentStrategy {
    String type();
    List<DrawingOp> execute(List<SemanticOp> ops);
}
