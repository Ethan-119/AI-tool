-- 绘画会话表
CREATE TABLE IF NOT EXISTS drawing_session (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id  VARCHAR(64)  NOT NULL COMMENT '业务会话标识(UUID)',
    title       VARCHAR(200) DEFAULT '未命名画作' COMMENT '会话名',
    canvas_state LONGTEXT     COMMENT '画布快照 JSON',
    step_count  INT          DEFAULT 0 COMMENT '已执行步数',
    status      VARCHAR(20)  DEFAULT 'active' COMMENT 'active / archived',
    create_time DATETIME     DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_session_id (session_id)
) COMMENT '绘画会话表';

-- 绘图记录表
CREATE TABLE IF NOT EXISTS drawing_record (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id    VARCHAR(64)   NOT NULL COMMENT '关联会话',
    step_num      INT           NOT NULL COMMENT '第几轮',
    original_text VARCHAR(1000) COMMENT 'ASR 识别原文本',
    command_json  TEXT          COMMENT '解析后的绘图指令 JSON',
    is_undone     TINYINT(1)    DEFAULT 0 COMMENT '是否已撤销',
    create_time   DATETIME      DEFAULT CURRENT_TIMESTAMP,
    KEY idx_session_id (session_id),
    KEY idx_session_step (session_id, step_num)
) COMMENT '绘图记录表';
