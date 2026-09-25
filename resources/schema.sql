-- MyYoutube MySQL 建表脚本 (由实体类推导, MySQL 8.0)
-- 主键两种风格: BIGINT 自增(AUTO) / 雪花ID(ASSIGN_ID, 应用生成, 不用 AUTO_INCREMENT)

CREATE DATABASE IF NOT EXISTS my_youtube DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE my_youtube;

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
    KEY `idx_video_id` (`video_id`)
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
    KEY `idx_followee_id` (`followee_id`)
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

-- 表情项表(聊天用)
CREATE TABLE IF NOT EXISTS `emoji_item` (
    `id`            BIGINT      NOT NULL AUTO_INCREMENT,
    `category_code` VARCHAR(32) NOT NULL COMMENT '分组, 如 nature/animals',
    `glyph`         VARCHAR(32) NOT NULL COMMENT '表情字符',
    `name_zh`       VARCHAR(64)          DEFAULT NULL COMMENT '中文名',
    `sort_order`    INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (`id`),
    KEY `idx_category_sort` (`category_code`, `sort_order`)
) ENGINE=InnoDB COMMENT='表情项';
