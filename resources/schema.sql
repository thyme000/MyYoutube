-- MyYoutube MySQL 建表脚本(微服务多 schema 版)
-- 主键两种风格: BIGINT 自增(AUTO) / 雪花ID(ASSIGN_ID, 应用生成, 不用 AUTO_INCREMENT)
-- 库划分与微服务一一对应: user_db(user 服务) / video_db(video 服务) / chat_db(chat 服务)
-- search_db 保存热搜历史；admin 服务无自有 MySQL 库

-- ==================== user_db: 用户库 ====================
CREATE DATABASE IF NOT EXISTS user_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE user_db;

-- 用户核心表
CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT       NOT NULL COMMENT '雪花ID',
    `username`        VARCHAR(64)  NOT NULL COMMENT '用户名, 唯一',
    `password`        VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    `role`            VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER/ADMIN/SUPER_ADMIN',
    `avatar_url`      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像URL',
    `email`           VARCHAR(128)          DEFAULT NULL,
    `phone`           VARCHAR(20)           DEFAULT NULL,
    `nickname`        VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `introduction`    VARCHAR(512)          DEFAULT '' COMMENT '个人介绍',
    `bg_image`        VARCHAR(512)          DEFAULT '' COMMENT '主页背景图URL',
    `fans`            BIGINT       NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `attention_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '关注数',
    `content_count`     BIGINT       NOT NULL DEFAULT 0 COMMENT '已发布内容数',
    `like_count`      BIGINT       NOT NULL DEFAULT 0 COMMENT '获赞数',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    -- 注册并发下 check-then-insert 的竞态由 DB 兜底; phone/email 可空, MySQL 唯一索引放行多个 NULL
    UNIQUE KEY `uk_phone` (`phone`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB COMMENT='用户';

CREATE TABLE IF NOT EXISTS `user_role_change` (
    `id` BIGINT NOT NULL PRIMARY KEY,
    `actor_id` BIGINT NOT NULL,
    `target_user_id` BIGINT NOT NULL,
    `previous_role` VARCHAR(16) NOT NULL,
    `new_role` VARCHAR(16) NOT NULL,
    `create_time` DATETIME NOT NULL,
    KEY `idx_role_change_target` (`target_user_id`, `create_time`, `id`),
    KEY `idx_role_change_actor` (`actor_id`, `create_time`, `id`)
) ENGINE=InnoDB COMMENT='用户角色变更记录';

-- 初始用户, 密码明文: password (BCrypt)
INSERT INTO `user` (`id`, `username`, `password`, `nickname`, `role`)
VALUES (1, 'thyme', '$2a$10$fUZbRM.EdgUr5NK12rt7q.1T1b55RnIZvT3228v1gbmZbgxpojK6u', 'thyme', 'SUPER_ADMIN');
INSERT INTO `user` (`id`, `username`, `password`, `nickname`)
VALUES (2, 'test', '$2a$10$fUZbRM.EdgUr5NK12rt7q.1T1b55RnIZvT3228v1gbmZbgxpojK6u', 'test');

-- ==================== video_db: 视频库(视频+互动+标签+通知) ====================
CREATE DATABASE IF NOT EXISTS video_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE video_db;

-- 统一内容池(消费者视角的跨内容表: 互动计数/分发/推荐/搜索都挂这里)
-- 发布时先插本表拿雪花id, 领域表(video/dynamic)主键与之相同; 以后新增内容类型只需注册一个 type
CREATE TABLE IF NOT EXISTS `content` (
    `id`            BIGINT      NOT NULL COMMENT '雪花ID, 与领域表主键相同',
    `type`          VARCHAR(16) NOT NULL COMMENT 'video/dynamic',
    `author_id`     BIGINT      NOT NULL COMMENT '作者ID',
    `audio_id`      BIGINT DEFAULT NULL COMMENT '内容使用的库内音频，跨内容类型统一关联',
    `title` VARCHAR(100) NOT NULL,
    `description` VARCHAR(512),
    `cover_object_key` VARCHAR(512),
    `category_code` VARCHAR(32),
    `tags` VARCHAR(512),
    `allow_comment` TINYINT NOT NULL DEFAULT 1,
    `region` VARCHAR(32),
    `is_delete` TINYINT NOT NULL DEFAULT 0,
    `status` VARCHAR(16) NOT NULL DEFAULT 'review' COMMENT 'review/published/banned',
    `visibility`    VARCHAR(16) NOT NULL DEFAULT 'public' COMMENT 'public/private, 随领域状态机',
    `index_version` BIGINT NOT NULL DEFAULT 1 COMMENT '内容索引快照版本，与审核锁独立',
    `play_count`    BIGINT      NOT NULL DEFAULT 0,
    `like_count`    BIGINT      NOT NULL DEFAULT 0,
    `collect_count` BIGINT      NOT NULL DEFAULT 0,
    `comment_count` BIGINT      NOT NULL DEFAULT 0,
    `share_count`   BIGINT      NOT NULL DEFAULT 0,
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_author_create` (`author_id`, `create_time`),
    KEY `idx_content_audio` (`audio_id`, `create_time`, `id`),
    KEY `idx_type_create` (`type`, `create_time`)
) ENGINE=InnoDB COMMENT='统一内容池';

-- 视频信息表
CREATE TABLE IF NOT EXISTS `video` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '与 content 相同',
    `object_key` VARCHAR(512) NOT NULL,
    `duration_ms` BIGINT NOT NULL
) ENGINE=InnoDB COMMENT='视频媒体';

-- 内容点赞表
CREATE TABLE IF NOT EXISTS `content_like` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT   NOT NULL,
    `content_id` BIGINT   NOT NULL,
    `is_liked`   TINYINT  NOT NULL DEFAULT 1 COMMENT '1已点赞 0已取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content` (`user_id`, `content_id`),
    KEY `idx_content_id` (`content_id`),
    -- 对账 COUNT(*) 需覆盖 is_liked, 否则每行回表
    KEY `idx_content_liked` (`content_id`, `is_liked`)
) ENGINE=InnoDB COMMENT='内容点赞';

-- 内容收藏表(视频/动态同池, 取消收藏即删行)
CREATE TABLE IF NOT EXISTS `content_collect` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT   NOT NULL,
    `content_id` BIGINT   NOT NULL,
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content` (`user_id`, `content_id`),
    KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB COMMENT='内容收藏';

-- 用户浏览历史表(视频/动态同池, 重复观看 upsert 置顶)
CREATE TABLE IF NOT EXISTS `user_view_history` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT   NOT NULL,
    `content_id` BIGINT   NOT NULL,
    `view_time`  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近观看时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content` (`user_id`, `content_id`),
    KEY `idx_user_view_time` (`user_id`, `view_time`)
) ENGINE=InnoDB COMMENT='用户浏览历史';

-- 统一内容续播状态
CREATE TABLE IF NOT EXISTS `content_playback` (
    `user_id` BIGINT NOT NULL,
    `content_id` BIGINT NOT NULL,
    `session_id` VARCHAR(64) NOT NULL,
    `session_started_at` BIGINT NOT NULL,
    `sequence` BIGINT NOT NULL,
    `watched_ms` BIGINT NOT NULL COMMENT '当前播放会话实际观看时长，不含拖动、翻图及后台时间',
    `video_position_ms` BIGINT DEFAULT NULL,
    `image_index` INT DEFAULT NULL,
    `image_elapsed_ms` BIGINT DEFAULT NULL,
    `audio_position_ms` BIGINT DEFAULT NULL,
    PRIMARY KEY (`user_id`, `content_id`)
) ENGINE=InnoDB COMMENT='统一内容续播状态';

-- 内容评论表
CREATE TABLE IF NOT EXISTS `content_comment` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT,
    `content_id`    BIGINT       NOT NULL,
    `user_id`       BIGINT       NOT NULL COMMENT '评论人',
    `path`          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '物化路径, 含自己: 根评论=自己id, 回复=父path.自己id',
    `level`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=根评论, >1为楼中楼回复',
    `reply_user_id` BIGINT                DEFAULT NULL COMMENT '被回复用户',
    `content`       TEXT         NOT NULL,
    `like_count`    INT          NOT NULL DEFAULT 0,
    `reply_count`   INT          NOT NULL DEFAULT 0,
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'visible' COMMENT 'visible/hidden/deleted/blocked',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_content_level_time` (`content_id`, `level`, `create_time`),
    KEY `idx_content_level_hot` (`content_id`, `level`, `like_count`),
    KEY `idx_path` (`path`)
) ENGINE=InnoDB COMMENT='内容评论';

-- 评论点赞表(评论点赞关系的权威来源, 对账与幂等判重都靠它)
CREATE TABLE IF NOT EXISTS `content_comment_like` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`     BIGINT   NOT NULL,
    `comment_id`  BIGINT   NOT NULL,
    `is_liked`    TINYINT  NOT NULL DEFAULT 1 COMMENT '1已点赞 0已取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_comment` (`user_id`, `comment_id`),
    KEY `idx_comment_liked` (`comment_id`, `is_liked`)
) ENGINE=InnoDB COMMENT='评论点赞';

-- 用户关注关系表
CREATE TABLE IF NOT EXISTS `user_follow` (
    `id`          BIGINT      NOT NULL COMMENT '雪花ID',
    `follower_id` BIGINT      NOT NULL COMMENT '关注者',
    `followee_id` BIGINT      NOT NULL COMMENT '被关注者',
    `remark`      VARCHAR(64)          DEFAULT '' COMMENT '备注别名',
    `is_deleted`  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '0关注中 1已取消',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
    KEY `idx_followee_id` (`followee_id`),
    -- 粉丝数/关注数对账 COUNT(*) 需覆盖 is_deleted, 否则每行回表
    KEY `idx_followee_deleted` (`followee_id`, `is_deleted`),
    KEY `idx_follower_deleted` (`follower_id`, `is_deleted`)
) ENGINE=InnoDB COMMENT='关注关系';

-- 站内通知表
CREATE TABLE IF NOT EXISTS `user_notification` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT,
    `receiver_id` BIGINT       NOT NULL COMMENT '接收人',
    `sender_id`   BIGINT                DEFAULT NULL COMMENT '触发人, 系统通知为NULL',
    `type`        VARCHAR(32)  NOT NULL COMMENT 'COMMENT_LIKE/COMMENT_REPLY/CONTENT_COMMENT等',
    `title`       VARCHAR(128)          DEFAULT '',
    `content`     VARCHAR(500)          DEFAULT '' COMMENT '摘要文案',
    `ref_type`    VARCHAR(16)           DEFAULT NULL COMMENT 'CONTENT/COMMENT/SYSTEM/CHAT',
    `ref_id`      BIGINT                DEFAULT NULL COMMENT '关联ID',
    `extra_json`  TEXT COMMENT '扩展字段JSON',
    `is_read`     TINYINT      NOT NULL DEFAULT 0,
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_receiver_time` (`receiver_id`, `create_time`)
) ENGINE=InnoDB COMMENT='站内通知';

-- 评论举报表
CREATE TABLE IF NOT EXISTS `comment_report` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT,
    `comment_id`       BIGINT       NOT NULL,
    `reporter_user_id` BIGINT       NOT NULL,
    `reason_code`      VARCHAR(16)  NOT NULL COMMENT '辱骂/涉黄/广告/引战/其他 的枚举名',
    `remark`           VARCHAR(255)          DEFAULT '' COMMENT '补充说明',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_comment_id` (`comment_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='评论举报';

-- 标签表
CREATE TABLE IF NOT EXISTS `tag` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `name`       VARCHAR(64) NOT NULL COMMENT '展示名',
    `slug`       VARCHAR(64) NOT NULL COMMENT '唯一键, URL用',
    `category_code` VARCHAR(32)       DEFAULT NULL COMMENT '所属类目, null=通用',
    `status`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `sort_order` INT         NOT NULL DEFAULT 0 COMMENT '越大越靠前',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`),
    KEY `idx_category` (`category_code`)
) ENGINE=InnoDB COMMENT='标签';

-- 内容-标签关联表
CREATE TABLE IF NOT EXISTS `content_tag` (
    `id`         BIGINT        NOT NULL AUTO_INCREMENT,
    `content_id` BIGINT        NOT NULL,
    `tag_id`     BIGINT        NOT NULL,
    `weight`     DECIMAL(5, 2)          DEFAULT 1.00 COMMENT '排序权重',
    `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_content_tag` (`content_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB COMMENT='内容标签关联';

-- 用户标签偏好表(推荐用)
CREATE TABLE IF NOT EXISTS `user_tag_preference` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT      NOT NULL,
    `tag_id`     BIGINT      NOT NULL,
    `score`      INT         NOT NULL DEFAULT 0 COMMENT '偏好分, 越大越偏好',
    `source`     VARCHAR(16) NOT NULL DEFAULT 'behavior' COMMENT 'behavior/manual',
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tag` (`user_id`, `tag_id`)
) ENGINE=InnoDB COMMENT='用户标签偏好';

-- 用户-内容行为流水表(推荐用)
CREATE TABLE IF NOT EXISTS `user_video_behavior` (
    `id`          BIGINT      NOT NULL COMMENT '雪花ID',
    `user_id`     BIGINT      NOT NULL,
    `content_id`  BIGINT      NOT NULL,
    `event_type`  VARCHAR(16) NOT NULL COMMENT 'play/finish/like/skip/dislike',
    `duration_ms` BIGINT               DEFAULT 0 COMMENT '停留/播放时长(毫秒)',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB COMMENT='用户视频行为流水';

-- 物品相似度表(ItemCF/Swing 离线产物, 推荐用; item_a/item_b 双向各存一行, 按内容类型分池)
CREATE TABLE IF NOT EXISTS `item_similarity` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT,
    `algorithm`    VARCHAR(16) NOT NULL COMMENT 'itemcf/swing',
    `content_type` VARCHAR(16) NOT NULL COMMENT '内容类型: video/dynamic/...',
    `item_a`       BIGINT      NOT NULL COMMENT '内容ID',
    `item_b`       BIGINT      NOT NULL COMMENT '邻居内容ID(与 item_a 同类型)',
    `score`        DOUBLE      NOT NULL COMMENT '相似度分',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_algo_pair` (`algorithm`, `item_a`, `item_b`),
    KEY `idx_algo_item` (`algorithm`, `item_a`, `score`)
) ENGINE=InnoDB COMMENT='物品相似度';

-- 音频库(动态/幻灯片合成共用; 用户上传自动入库, 默认公开, 可转私密)
CREATE TABLE IF NOT EXISTS `audio` (
    `id`                      BIGINT        NOT NULL COMMENT '雪花ID',
    `uploader_id`             BIGINT        NOT NULL COMMENT '上传者',
    `title`                   VARCHAR(100)  DEFAULT '' COMMENT '展示名',
    `request_id`              VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `request_fingerprint`     VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    `source_object_key`       VARCHAR(512)  NOT NULL COMMENT '待后台固定的上传原件',
    `source_cover_object_key` VARCHAR(512)  DEFAULT NULL,
    `upload_size`             BIGINT        NOT NULL,
    `upload_content_type`     VARCHAR(128)  NOT NULL,
    `cover_upload_size`       BIGINT        DEFAULT NULL,
    `cover_upload_content_type` VARCHAR(128) DEFAULT NULL,
    `upload_deadline`         DATETIME      NOT NULL,
    `upload_after`            DATETIME      DEFAULT NULL,
    `object_key`              VARCHAR(512)  DEFAULT NULL COMMENT '后台验证通过的不可变音频',
    `cover_object_key`        VARCHAR(512)  DEFAULT NULL COMMENT '仅供人工审核的不可变封面',
    `duration_ms`             INT           DEFAULT NULL COMMENT '服务端实际解码时长(毫秒)',
    `file_size`               BIGINT        DEFAULT NULL,
    `mime`                    VARCHAR(64)   DEFAULT NULL,
    `processing_status`       VARCHAR(16)   NOT NULL DEFAULT 'uploading',
    `review_status`           VARCHAR(16)   NOT NULL DEFAULT 'pending',
    `preparation_attempt`     INT           NOT NULL DEFAULT 0,
    `preparation_retry`       TINYINT       NOT NULL DEFAULT 0,
    `preparation_after`       DATETIME      DEFAULT NULL,
    `processing_deadline`     DATETIME      DEFAULT NULL,
    `error_message`           VARCHAR(500)  DEFAULT NULL,
    `analysis_status`         VARCHAR(24)   DEFAULT NULL,
    `analysis_attempt`        INT           NOT NULL DEFAULT 0,
    `transcript_key`          VARCHAR(512)  DEFAULT NULL,
    `machine_result_json`     MEDIUMTEXT,
    `review_reason`           VARCHAR(4000) DEFAULT NULL,
    `usage_count`             BIGINT        NOT NULL DEFAULT 0 COMMENT '异步维护的公开关联内容数，仅管理员可见',
    `index_version`           BIGINT        NOT NULL DEFAULT 1,
    `indexed_version`         BIGINT        NOT NULL DEFAULT 0,
    `index_after`             DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `index_pending_since`     DATETIME      DEFAULT CURRENT_TIMESTAMP,
    `visibility`              VARCHAR(16)   NOT NULL DEFAULT 'public' COMMENT 'public/private',
    `create_time`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`               TINYINT       NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_audio_request` (`uploader_id`, `request_id`),
    UNIQUE KEY `uk_audio_upload` (`source_object_key`),
    UNIQUE KEY `uk_audio_cover_upload` (`source_cover_object_key`),
    KEY `idx_audio_upload` (`processing_status`, `upload_after`, `id`),
    KEY `idx_audio_processing` (`processing_status`, `review_status`, `is_delete`, `preparation_after`, `id`),
    KEY `idx_audio_review_status` (`review_status`, `id`),
    KEY `idx_audio_index` (`index_after`, `id`),
    KEY `idx_audio_index_pending` (`index_pending_since`, `id`),
    KEY `idx_uploader` (`uploader_id`, `create_time`),
    KEY `idx_visibility_create` (`visibility`, `review_status`, `processing_status`, `is_delete`, `create_time`)
) ENGINE=InnoDB COMMENT='音频库';

CREATE TABLE IF NOT EXISTS `audio_review_record` (
    `id`           BIGINT        NOT NULL,
    `audio_id`     BIGINT        NOT NULL,
    `source`       VARCHAR(16)   NOT NULL,
    `decision`     VARCHAR(32)   NOT NULL,
    `reason`       VARCHAR(4000),
    `payload_json` MEDIUMTEXT,
    `attempt`      INT,
    `actor_id`     BIGINT,
    `create_time`  DATETIME      NOT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_audio_review` (`audio_id`, `create_time`, `id`)
) ENGINE=InnoDB COMMENT='音频独立审核记录';

-- 图集配乐动态(主体表; 时间轴/焦点等嵌套结构存 JSON 列)
CREATE TABLE IF NOT EXISTS `dynamic` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '与 content 相同',
    `media_json` JSON NOT NULL COMMENT '有序图片、画布、转场和可选原始音频快照，无签名URL'
) ENGINE=InnoDB COMMENT='原生图集媒体';

-- 可靠管线事件与业务状态同库、同事务记录。
CREATE TABLE IF NOT EXISTS pipeline_event_outbox (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY,
    aggregate_type VARCHAR(16) NOT NULL,
    aggregate_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    event_sequence BIGINT NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    payload MEDIUMTEXT NOT NULL,
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    lease_token VARCHAR(64),
    lease_until DATETIME(6),
    sent_at DATETIME(6),
    quarantine_reason VARCHAR(128),
    created_at DATETIME(6) NOT NULL,
    UNIQUE KEY uk_event_sequence(aggregate_type, aggregate_id, event_sequence),
    KEY idx_outbox_pending(status, quarantine_reason, created_at, event_id),
    KEY idx_outbox_lease(status, quarantine_reason, lease_until, event_id),
    KEY idx_outbox_cleanup(status, quarantine_reason, sent_at, event_id)
) ENGINE=InnoDB;

-- ==================== transcode_db: 转码库(transcode-svc) ====================
CREATE DATABASE IF NOT EXISTS transcode_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE transcode_db;

CREATE TABLE IF NOT EXISTS pipeline_event_outbox LIKE video_db.pipeline_event_outbox;

-- 懒转码任务表(动态成片生成; task_key 为元数据内容哈希, 同 key 在途任务复用即幂等)
CREATE TABLE IF NOT EXISTS `transcode_task` (
    `id`                BIGINT       NOT NULL COMMENT '雪花ID',
    `task_key`          VARCHAR(64)  NOT NULL COMMENT '请求内容SHA-256, 兼作成片缓存key',
    `content_id`        BIGINT       NOT NULL COMMENT '来源内容ID',
    `user_id`           BIGINT       NOT NULL COMMENT '触发下载的用户ID',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/FAILED/EXPIRED',
    `request_json`      TEXT         NOT NULL COMMENT '转码参数快照(canvas/transition/timeline/audio)',
    `result_object_key` VARCHAR(512)          DEFAULT '' COMMENT '成片objectKey, LRU清理后置空',
    `error_message`     VARCHAR(500)          DEFAULT NULL,
    `next_dispatch_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_key_status` (`task_key`, `status`),
    KEY `idx_dispatch` (`status`, `next_dispatch_at`, `id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB COMMENT='动态成片转码任务';

-- ==================== video_db: 视频上传草稿 ====================
USE video_db;

-- 视频上传后转码 READY；图集原生素材校验后 READY；提交冻结为 submitted。
CREATE TABLE IF NOT EXISTS `media_draft` (
    event_sequence BIGINT NOT NULL DEFAULT 0,
    media_version BIGINT NOT NULL DEFAULT 0,
    preparation_id BIGINT NOT NULL,
    preparation_attempt INT NOT NULL DEFAULT 0,
    preparation_retry TINYINT NOT NULL DEFAULT 0,
    preparation_after DATETIME,
    submission_state VARCHAR(16) NOT NULL DEFAULT 'none' COMMENT '发布意图状态，取值见 DraftSubmissionState',
    submission_revision INT,
    submission_attempts INT NOT NULL DEFAULT 0,
    submission_after DATETIME,
    submission_deadline DATETIME,
    submission_error VARCHAR(500),
    KEY idx_draft_submission(submission_state, submission_after, id),
    KEY idx_draft_user_submission(user_id, submission_state, create_time, id),
    expires_at DATETIME NOT NULL,
    KEY idx_draft_expiry(status, expires_at, id),
    prepared_json TEXT,
    prepared_cover_object_key VARCHAR(512),
    `id`                BIGINT       NOT NULL COMMENT '雪花ID, 与后续video/content共用',
    `user_id`           BIGINT       NOT NULL COMMENT '属主',
    `type`              VARCHAR(16)  NOT NULL COMMENT 'video/dynamic',
    `title`             VARCHAR(100)          DEFAULT NULL,
    `description`       VARCHAR(512)          DEFAULT NULL,
    `visibility`        VARCHAR(16)  NOT NULL DEFAULT 'public' COMMENT 'public/private',
    `allow_comment`     TINYINT      NOT NULL DEFAULT 1,
    `cover_object_key` VARCHAR(512),
    `revision` INT NOT NULL DEFAULT 0,
    `media_json`        JSON                 DEFAULT NULL COMMENT '原生图集媒体快照，无签名URL',
    `object_key`        VARCHAR(512)          DEFAULT NULL COMMENT '视频原件；图集为空',
    `original_filename` VARCHAR(256)          DEFAULT NULL,
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'uploading' COMMENT 'uploading/processing/ready/failed/expired/submitted',
    `duration_ms`       BIGINT                DEFAULT NULL COMMENT 'FFprobe 探测时长',
    `error_message`     VARCHAR(500)          DEFAULT NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_object_key` (`object_key`),
    KEY idx_draft_preparation(status, preparation_after, id),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB COMMENT='内容草稿';

-- ==================== transcode_db: 媒体处理任务(DAG节点) ====================
USE transcode_db;

CREATE TABLE IF NOT EXISTS media_pipeline (
    event_sequence BIGINT NOT NULL DEFAULT 0,
    id BIGINT NOT NULL PRIMARY KEY,
    content_id BIGINT NOT NULL,
    input_json JSON NOT NULL,
    status VARCHAR(32) NOT NULL,
    received TINYINT NOT NULL DEFAULT 0,
    manifest_json JSON,
    submitted_at DATETIME NOT NULL,
    completed_at DATETIME,
    notify_after DATETIME,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_pipeline_content(content_id),
    KEY idx_pipeline_delivery(status, received, notify_after, id),
    KEY idx_pipeline_metrics_delivery(status, received, completed_at, id),
    KEY idx_pipeline_status(status, id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS media_task (
    event_sequence BIGINT NOT NULL DEFAULT 0,
    id BIGINT NOT NULL PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    pipeline_id BIGINT,
    retry_batch_id BIGINT,
    failure_code VARCHAR(80),
    dependency_service VARCHAR(16),
    phase VARCHAR(16) NOT NULL,
    task_type VARCHAR(16) NOT NULL,
    status VARCHAR(16) NOT NULL,
    completion_ack TINYINT NOT NULL DEFAULT 0,
    attempt INT NOT NULL DEFAULT 0,
    retry_count INT NOT NULL DEFAULT 0,
    draft_input_json JSON,
    dependencies_json JSON NOT NULL,
    result_json JSON,
    error_message VARCHAR(500),
    ready_at DATETIME,
    started_at DATETIME,
    next_retry_at DATETIME,
    unavailable_deadline DATETIME,
    dispatch_after DATETIME,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_owner_phase_type(owner_id, phase, task_type),
    UNIQUE KEY uk_pipeline_phase_type(pipeline_id, phase, task_type),
    KEY idx_task_metrics_ready(status, ready_at, id),
    KEY idx_task_batch(retry_batch_id, status, id),
    KEY idx_task_dispatch(status, dispatch_after, id),
    KEY idx_task_retry(status, next_retry_at, id),
    KEY idx_task_lease(status, update_time, id),
    KEY idx_task_delivery(phase, completion_ack, status, id)
) ENGINE=InnoDB;

USE video_db;
CREATE TABLE IF NOT EXISTS content_understanding (
    event_sequence BIGINT NOT NULL DEFAULT 0,
    content_id BIGINT NOT NULL PRIMARY KEY,
    pipeline_id BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL DEFAULT 'WAITING_PIPELINE',
    input_json JSON NOT NULL,
    submission_ack TINYINT NOT NULL DEFAULT 0,
    received TINYINT NOT NULL DEFAULT 0,
    retry_requested TINYINT NOT NULL DEFAULT 0,
    subtitle_key VARCHAR(512),
    segments_key VARCHAR(512),
    ocr_key VARCHAR(512),
    summary_key VARCHAR(512),
    tag_key VARCHAR(512),
    summary TEXT,
    chapters_json JSON,
    review_attempt INT NOT NULL DEFAULT 0,
    submitted_at DATETIME NOT NULL,
    received_at DATETIME,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_understanding_pipeline(pipeline_id),
    KEY idx_understanding_state(state, content_id),
    KEY idx_understanding_submission(submission_ack, content_id),
    KEY idx_understanding_retry(retry_requested, content_id)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS content_review_record (
    id BIGINT NOT NULL PRIMARY KEY,
    content_id BIGINT NOT NULL,
    pipeline_id BIGINT,
    attempt INT NOT NULL,
    source VARCHAR(16) NOT NULL,
    decision VARCHAR(16) NOT NULL,
    risk_score DOUBLE DEFAULT NULL,
    confidence DOUBLE DEFAULT NULL,
    reason TEXT NOT NULL,
    evidence_json JSON,
    model VARCHAR(128),
    rules_version VARCHAR(64),
    actor_id BIGINT DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_review_content (content_id, create_time),
    UNIQUE KEY uk_review_attempt(pipeline_id, attempt, source, decision)
) ENGINE=InnoDB;
CREATE TABLE IF NOT EXISTS content_publication_event (
    id BIGINT NOT NULL PRIMARY KEY,
    content_id BIGINT NOT NULL,
    author_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    delta INT NOT NULL,
    audio_id BIGINT DEFAULT NULL,
    audio_delta INT NOT NULL DEFAULT 0,
    audio_count_done TINYINT NOT NULL DEFAULT 1,
    reason TEXT,
    user_count_done TINYINT NOT NULL DEFAULT 0,
    search_done TINYINT NOT NULL DEFAULT 0,
    notice_done TINYINT NOT NULL DEFAULT 0,
    completed TINYINT NOT NULL DEFAULT 0,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_publication_metrics_age (completed, create_time, id),
    KEY idx_publication_pending (completed, id),
    KEY idx_author_count(author_id, user_count_done, id),
    KEY idx_audio_count(audio_id, audio_count_done, id)
) ENGINE=InnoDB;
USE user_db;
CREATE TABLE IF NOT EXISTS user_counter_event (
    event_id VARCHAR(80) NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB;

USE transcode_db;

CREATE TABLE IF NOT EXISTS media_preparation (
    id BIGINT NOT NULL PRIMARY KEY,
    draft_id BIGINT NOT NULL,
    media_version BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    request_json TEXT NOT NULL,
    parameter_version VARCHAR(64) NOT NULL,
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
    reclaim_after DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    retired_at DATETIME,
    KEY idx_preparation_reclaim(reclaim_after, id),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_preparation_draft_version(draft_id, media_version)
);
CREATE TABLE IF NOT EXISTS media_preparation_item (
    id BIGINT NOT NULL PRIMARY KEY,
    preparation_id BIGINT NOT NULL,
    object_owner_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    item_index INT NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    source_json TEXT,
    result_json TEXT,
    status VARCHAR(16) NOT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_preparation_item(preparation_id, role, item_index),
    KEY idx_preparation_object(object_key, preparation_id)
);

USE transcode_db;
CREATE TABLE IF NOT EXISTS media_retry_batch (
    event_sequence BIGINT NOT NULL DEFAULT 0,
    id BIGINT NOT NULL PRIMARY KEY,
    request_id VARCHAR(128) NOT NULL,
    actor_id BIGINT NOT NULL,
    status VARCHAR(32) NOT NULL,
    active TINYINT,
    selection_json TEXT NOT NULL,
    result_json MEDIUMTEXT,
    max_concurrency INT NOT NULL,
    created_at DATETIME NOT NULL,
    completed_at DATETIME,
    UNIQUE KEY uk_retry_request(request_id),
    UNIQUE KEY uk_retry_active(active)
);

-- ==================== search_db: 热搜历史 ====================
CREATE DATABASE IF NOT EXISTS search_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
USE search_db;

CREATE TABLE IF NOT EXISTS hot_search_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    stat_date DATE NOT NULL,
    happened_at BIGINT NOT NULL COMMENT '真实采集时间，UTC epoch 毫秒',
    event_hour BIGINT NOT NULL COMMENT '原始事件小时编号，不随归档日期顺延',
    user_id BIGINT NOT NULL,
    raw_keyword TEXT NOT NULL,
    grouping_keyword VARCHAR(64) NOT NULL,
    group_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    vector_version VARCHAR(255) NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE KEY uk_hot_search_event(event_id),
    KEY idx_hot_search_event_group(stat_date, group_id, happened_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;

CREATE TABLE IF NOT EXISTS hot_search_daily_group (
    stat_date DATE NOT NULL,
    group_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    group_name VARCHAR(64) NOT NULL,
    vector_version VARCHAR(255) NOT NULL,
    search_count BIGINT UNSIGNED NOT NULL,
    user_count BIGINT UNSIGNED NOT NULL,
    score BIGINT UNSIGNED NOT NULL,
    rank_no BIGINT UNSIGNED NOT NULL,
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY (stat_date, group_id),
    KEY idx_hot_search_daily_rank(stat_date, rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin;
