package com.voicedraw.model;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("drawing_record")
public class DrawingRecord {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String sessionId;

    private Integer stepNum;

    private String originalText;

    private String commandJson;

    private Integer isUndone;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
