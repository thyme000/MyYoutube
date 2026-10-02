-- MyYoutube MySQL 建表脚本(微服务多 schema 版)
-- 主键两种风格: BIGINT 自增(AUTO) / 雪花ID(ASSIGN_ID, 应用生成, 不用 AUTO_INCREMENT)
-- 库划分与微服务一一对应: user_db(user 服务) / video_db(video 服务) / chat_db(chat 服务)
-- search/admin 服务无自有 MySQL 库

-- ==================== user_db: 用户库 ====================
CREATE DATABASE IF NOT EXISTS user_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE user_db;

-- 用户核心表
CREATE TABLE IF NOT EXISTS `user` (
    `id`              BIGINT       NOT NULL COMMENT '雪花ID',
    `username`        VARCHAR(64)  NOT NULL COMMENT '用户名, 唯一',
    `password`        VARCHAR(100) NOT NULL COMMENT 'BCrypt加密密码',
    `avatar_url`      VARCHAR(512) NOT NULL DEFAULT '' COMMENT '头像URL',
    `email`           VARCHAR(128)          DEFAULT NULL,
    `phone`           VARCHAR(20)           DEFAULT NULL,
    `nickname`        VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `introduction`    VARCHAR(512)          DEFAULT '' COMMENT '个人介绍',
    `bg_image`        VARCHAR(512)          DEFAULT '' COMMENT '主页背景图URL',
    `fans`            BIGINT       NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `attention_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '关注数',
    `video_count`     BIGINT       NOT NULL DEFAULT 0 COMMENT '视频发布数',
    `like_count`      BIGINT       NOT NULL DEFAULT 0 COMMENT '获赞数',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`)
) ENGINE=InnoDB COMMENT='用户';

-- 初始用户, 密码明文: password (BCrypt)
INSERT INTO `user` (`id`, `username`, `password`, `nickname`)
VALUES (1, 'thyme', '$2a$10$fUZbRM.EdgUr5NK12rt7q.1T1b55RnIZvT3228v1gbmZbgxpojK6u', 'thyme');
INSERT INTO `user` (`id`, `username`, `password`, `nickname`)
VALUES (2, 'test', '$2a$10$fUZbRM.EdgUr5NK12rt7q.1T1b55RnIZvT3228v1gbmZbgxpojK6u', 'test');

-- ==================== video_db: 视频库(视频+互动+标签+通知) ====================
CREATE DATABASE IF NOT EXISTS video_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE video_db;

-- 视频信息表
CREATE TABLE IF NOT EXISTS `video` (
    `id`            BIGINT       NOT NULL COMMENT '雪花ID',
    `title`         VARCHAR(200) NOT NULL,
    `description`   TEXT COMMENT '简介',
    `cover_url`     VARCHAR(512)          DEFAULT '' COMMENT '封面',
    `video_url`     VARCHAR(512)          DEFAULT '' COMMENT '视频文件objectKey/URL',
    `duration`      INT                   DEFAULT 0 COMMENT '时长(秒)',
    `video_type`    VARCHAR(16)           DEFAULT NULL COMMENT 'short/long',
    `category`      VARCHAR(32)           DEFAULT NULL COMMENT '分类code',
    `tags`          VARCHAR(512)          DEFAULT NULL COMMENT '标签, 逗号分隔',
    `author_id`     BIGINT       NOT NULL COMMENT '作者ID',
    `play_count`    BIGINT       NOT NULL DEFAULT 0,
    `like_count`    BIGINT       NOT NULL DEFAULT 0,
    `collect_count` BIGINT       NOT NULL DEFAULT 0,
    `comment_count` BIGINT       NOT NULL DEFAULT 0,
    `share_count`   BIGINT       NOT NULL DEFAULT 0,
    `status`        VARCHAR(16)           DEFAULT NULL COMMENT 'draft/review/published/banned',
    `visibility`    VARCHAR(16)           DEFAULT NULL COMMENT 'public/private/unlisted',
    `allow_comment` TINYINT               DEFAULT 1 COMMENT '0不允许 1允许评论',
    `region`        VARCHAR(32)           DEFAULT NULL COMMENT '发布地区',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    `is_delete`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    PRIMARY KEY (`id`),
    KEY `idx_author_id` (`author_id`),
    KEY `idx_status_create` (`status`, `create_time`)
) ENGINE=InnoDB COMMENT='视频';

-- 视频点赞表
CREATE TABLE IF NOT EXISTS `video_like` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT,
    `user_id`    BIGINT   NOT NULL,
    `video_id`   BIGINT   NOT NULL,
    `is_liked`   TINYINT  NOT NULL DEFAULT 1 COMMENT '1已点赞 0已取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_video` (`user_id`, `video_id`),
    KEY `idx_video_id` (`video_id`),
    -- 对账 COUNT(*) 需覆盖 is_liked, 否则每行回表
    KEY `idx_video_liked` (`video_id`, `is_liked`)
) ENGINE=InnoDB COMMENT='视频点赞';

-- 视频评论表
CREATE TABLE IF NOT EXISTS `video_comment` (
    `id`            BIGINT        NOT NULL AUTO_INCREMENT,
    `video_id`      BIGINT        NOT NULL,
    `user_id`       BIGINT        NOT NULL COMMENT '评论人',
    `parent_id`     BIGINT        NOT NULL DEFAULT 0 COMMENT '0=一级评论',
    `root_id`       BIGINT                 DEFAULT NULL COMMENT '根评论ID, 一级评论为NULL',
    `reply_user_id` BIGINT                 DEFAULT NULL COMMENT '被回复用户',
    `content`       TEXT          NOT NULL,
    `like_count`    INT           NOT NULL DEFAULT 0,
    `reply_count`   INT           NOT NULL DEFAULT 0,
    `status`        VARCHAR(16)   NOT NULL DEFAULT 'visible' COMMENT 'visible/hidden/deleted/blocked',
    `create_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`   DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_video_status` (`video_id`, `status`),
    KEY `idx_root_id` (`root_id`)
) ENGINE=InnoDB COMMENT='视频评论';

-- 评论点赞表(评论点赞关系的权威来源, 对账与幂等判重都靠它)
CREATE TABLE IF NOT EXISTS `video_comment_like` (
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
    `type`        VARCHAR(32)  NOT NULL COMMENT 'COMMENT_LIKE/COMMENT_REPLY/VIDEO_COMMENT等',
    `title`       VARCHAR(128)          DEFAULT '',
    `content`     VARCHAR(500)          DEFAULT '' COMMENT '摘要文案',
    `ref_type`    VARCHAR(16)           DEFAULT NULL COMMENT 'VIDEO/COMMENT',
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
    `status`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `sort_order` INT         NOT NULL DEFAULT 0 COMMENT '越大越靠前',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`)
) ENGINE=InnoDB COMMENT='标签';

-- 视频-标签关联表
CREATE TABLE IF NOT EXISTS `video_tag` (
    `id`         BIGINT        NOT NULL AUTO_INCREMENT,
    `video_id`   BIGINT        NOT NULL,
    `tag_id`     BIGINT        NOT NULL,
    `weight`     DECIMAL(5, 2)          DEFAULT 1.00 COMMENT '排序权重',
    `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_tag` (`video_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB COMMENT='视频标签关联';

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

-- 用户-视频行为流水表(推荐用)
CREATE TABLE IF NOT EXISTS `user_video_behavior` (
    `id`          BIGINT      NOT NULL COMMENT '雪花ID',
    `user_id`     BIGINT      NOT NULL,
    `video_id`    BIGINT      NOT NULL,
    `event_type`  VARCHAR(16) NOT NULL COMMENT 'expose/play/finish/like/skip/dislike',
    `duration_ms` BIGINT               DEFAULT 0 COMMENT '停留/播放时长(毫秒)',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_video_id` (`video_id`)
) ENGINE=InnoDB COMMENT='用户视频行为流水';

-- ==================== chat_db: 聊天库 ====================
CREATE DATABASE IF NOT EXISTS chat_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE chat_db;

-- 表情项表(聊天用; 聊天消息本体存 MongoDB)
CREATE TABLE IF NOT EXISTS `emoji_item` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `category_code` VARCHAR(32) NOT NULL COMMENT '分组, 如 nature/animals',
    `glyph`         VARCHAR(32) NOT NULL COMMENT '表情字符',
    `name_zh`       VARCHAR(64)          DEFAULT NULL COMMENT '中文名',
    `sort_order`    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_category_sort` (`category_code`, `sort_order`)
) ENGINE=InnoDB COMMENT='表情项';

-- ==================== transcode_db: 转码库(transcode-svc) ====================
CREATE DATABASE IF NOT EXISTS transcode_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE transcode_db;

-- 懒转码任务表(动态成片生成; task_key 为元数据内容哈希, 同 key 在途任务复用即幂等)
CREATE TABLE IF NOT EXISTS `transcode_task` (
    `id`                BIGINT       NOT NULL COMMENT '雪花ID',
    `task_key`          VARCHAR(64)  NOT NULL COMMENT '请求内容SHA-256, 兼作成片缓存key',
    `dynamic_id`        BIGINT       NOT NULL COMMENT '来源动态ID',
    `user_id`           BIGINT       NOT NULL COMMENT '触发下载的用户ID',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/PROCESSING/DONE/FAILED',
    `request_json`      TEXT         NOT NULL COMMENT '转码参数快照(canvas/transition/timeline/audio)',
    `result_object_key` VARCHAR(512)          DEFAULT '' COMMENT '成片objectKey, LRU清理后置空',
    `error_message`     VARCHAR(500)          DEFAULT NULL,
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_key_status` (`task_key`, `status`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB COMMENT='动态成片转码任务';
