package com.voicedraw.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("drawing_session")
public class DrawingSession {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private String title;

    private String canvasState;

    private Integer stepCount;

    private String status;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
