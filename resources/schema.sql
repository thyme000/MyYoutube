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
    `email`           VARCHAR(128)          DEFAULT NULL COMMENT '邮箱, 可空, 非空时唯一',
    `phone`           VARCHAR(20)           DEFAULT NULL COMMENT '手机号, 可空, 非空时唯一',
    `nickname`        VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    `introduction`    VARCHAR(512)          DEFAULT '' COMMENT '个人介绍',
    `bg_image`        VARCHAR(512)          DEFAULT '' COMMENT '主页背景图URL',
    `fans`            BIGINT       NOT NULL DEFAULT 0 COMMENT '粉丝数',
    `attention_count` BIGINT       NOT NULL DEFAULT 0 COMMENT '关注数',
    `like_count`      BIGINT       NOT NULL DEFAULT 0 COMMENT '获赞数',
    `like_version`    BIGINT       NOT NULL DEFAULT 0 COMMENT '已收下的作者获赞版本',
    `create_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '注册时间',
    `update_time`     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `is_delete`       TINYINT      NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_username` (`username`),
    -- 注册并发下 check-then-insert 的竞态由 DB 兜底; phone/email 可空, MySQL 唯一索引放行多个 NULL
    UNIQUE KEY `uk_phone` (`phone`),
    UNIQUE KEY `uk_email` (`email`)
) ENGINE=InnoDB COMMENT='用户';

CREATE TABLE IF NOT EXISTS `user_role_change` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    `actor_id` BIGINT NOT NULL COMMENT '操作人',
    `target_user_id` BIGINT NOT NULL COMMENT '被变更用户',
    `previous_role` VARCHAR(16) NOT NULL COMMENT '变更前角色 USER/ADMIN/SUPER_ADMIN',
    `new_role` VARCHAR(16) NOT NULL COMMENT '变更后角色 USER/ADMIN/SUPER_ADMIN',
    `create_time` DATETIME NOT NULL COMMENT '变更时间',
    KEY `idx_role_change_target` (`target_user_id`, `create_time`, `id`),
    KEY `idx_role_change_actor` (`actor_id`, `create_time`, `id`)
) ENGINE=InnoDB COMMENT='用户角色变更记录';

CREATE TABLE IF NOT EXISTS `user_check_in` (
    `id` BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    `user_id` BIGINT NOT NULL COMMENT '签到用户',
    `check_in_date` DATE NOT NULL COMMENT '上海时区签到日期',
    `checked_in_at` DATETIME NOT NULL COMMENT '上海时区首次签到时间',
    UNIQUE KEY `uk_check_in_user_date` (`user_id`, `check_in_date`),
    KEY `idx_check_in_date_user` (`check_in_date`, `user_id`)
) ENGINE=InnoDB COMMENT='用户手动签到记录';

CREATE TABLE IF NOT EXISTS `user_check_in_month_snapshot` (
    `month_start` DATE NOT NULL PRIMARY KEY COMMENT '已结束月份的第一天',
    `signed_users` BIGINT NOT NULL COMMENT '月内去重签到人数',
    `check_ins` BIGINT NOT NULL COMMENT '月内签到总人次',
    `year_signed_users` BIGINT DEFAULT NULL COMMENT '全年去重签到人数，仅12月快照保存',
    `year_check_ins` BIGINT DEFAULT NULL COMMENT '全年签到总人次，仅12月快照保存',
    `daily_counts` TEXT NOT NULL COMMENT '按日期顺序保存每日人数的 JSON 数组，含零签到日'
) ENGINE=InnoDB COMMENT='签到月度统计快照';

-- 用户关注关系表
CREATE TABLE IF NOT EXISTS `user_follow` (
    `id`          BIGINT      NOT NULL COMMENT '雪花ID',
    `follower_id` BIGINT      NOT NULL COMMENT '关注者',
    `followee_id` BIGINT      NOT NULL COMMENT '被关注者',
    `remark`      VARCHAR(64)          DEFAULT '' COMMENT '备注别名',
    `is_deleted`  TINYINT(1)  NOT NULL DEFAULT 0 COMMENT '0关注中 1已取消',
    `create_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次关注时间',
    `update_time` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近状态变更时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_follower_followee` (`follower_id`, `followee_id`),
    KEY `idx_followee_id` (`followee_id`),
    -- 粉丝数/关注数对账 COUNT(*) 需覆盖 is_deleted, 否则每行回表
    KEY `idx_followee_deleted` (`followee_id`, `is_deleted`),
    KEY `idx_follower_deleted` (`follower_id`, `is_deleted`)
) ENGINE=InnoDB COMMENT='关注关系';

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
    `title` VARCHAR(100) NOT NULL COMMENT '标题',
    `description` VARCHAR(512) COMMENT '简介',
    `cover_object_key` VARCHAR(512) COMMENT '封面对象键, 无签名URL',
    `category_code` VARCHAR(32) COMMENT '类目编码, 由内容理解写入',
    `tags` VARCHAR(512) COMMENT '标签名, 逗号分隔, 由内容理解写入',
    `allow_comment` TINYINT NOT NULL DEFAULT 1 COMMENT '1允许评论 0关闭',
    `region` VARCHAR(32) COMMENT '地区',
    `is_delete` TINYINT NOT NULL DEFAULT 0 COMMENT '0正常 1已删除',
    `status` VARCHAR(16) NOT NULL DEFAULT 'review' COMMENT 'review/published/banned',
    `visibility`    VARCHAR(16) NOT NULL DEFAULT 'public' COMMENT 'public/private, 随领域状态机',
    `index_version` BIGINT NOT NULL DEFAULT 1 COMMENT '内容索引快照版本，与审核锁独立',
    `play_count`    BIGINT      NOT NULL DEFAULT 0 COMMENT '播放数',
    `like_count`    BIGINT      NOT NULL DEFAULT 0 COMMENT '点赞数',
    `collect_count` BIGINT      NOT NULL DEFAULT 0 COMMENT '收藏数',
    `comment_count` BIGINT      NOT NULL DEFAULT 0 COMMENT '评论数',
    `share_count`   BIGINT      NOT NULL DEFAULT 0 COMMENT '分享次数',
    `create_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_author_create` (`author_id`, `create_time`),
    KEY `idx_content_audio` (`audio_id`, `create_time`, `id`),
    KEY `idx_type_create` (`type`, `create_time`)
) ENGINE=InnoDB COMMENT='统一内容池';

CREATE TABLE IF NOT EXISTS `author_content_stat` (
    `author_id` BIGINT NOT NULL COMMENT '作者用户ID',
    `type` VARCHAR(16) NOT NULL COMMENT '内容类型 video/dynamic',
    `visibility` VARCHAR(16) NOT NULL COMMENT 'public/private',
    `published_count` BIGINT NOT NULL DEFAULT 0 COMMENT '该维度下已发布且未删除的作品数',
    PRIMARY KEY (`author_id`, `type`, `visibility`)
) ENGINE=InnoDB COMMENT='作者已发布且未删除的作品基数，不随音频状态批量调整';

CREATE TABLE IF NOT EXISTS `audio_author_usage` (
    `audio_id` BIGINT NOT NULL COMMENT '音频ID',
    `author_id` BIGINT NOT NULL COMMENT '使用该音频的作者',
    `type` VARCHAR(16) NOT NULL COMMENT '内容类型 video/dynamic',
    `visibility` VARCHAR(16) NOT NULL COMMENT 'public/private',
    `reference_count` BIGINT NOT NULL DEFAULT 0 COMMENT '该维度下仍引用此音频的已发布作品数, 归零后保留行',
    PRIMARY KEY (`audio_id`, `author_id`, `type`, `visibility`),
    KEY `idx_author_usage` (`author_id`, `type`, `visibility`, `audio_id`)
) ENGINE=InnoDB COMMENT='作者已发布作品的音频引用汇总，零引用行保留历史使用事实';

-- 视频信息表
CREATE TABLE IF NOT EXISTS `video` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '与 content 相同',
    `object_key` VARCHAR(512) NOT NULL COMMENT '源视频对象键',
    `duration_ms` BIGINT NOT NULL COMMENT '时长(毫秒)',
    `playback_json` JSON NOT NULL COMMENT '源视频尺寸及 HLS 档位资源、编码、峰值/平均码率、帧率，无签名URL'
) ENGINE=InnoDB COMMENT='视频媒体';

-- 内容点赞表，取消点赞即删除关系
CREATE TABLE IF NOT EXISTS `content_like` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`    BIGINT   NOT NULL COMMENT '点赞用户',
    `content_id` BIGINT   NOT NULL COMMENT '内容ID',
    `credited_tag_ids` JSON NOT NULL COMMENT '本次点赞实际加分的标签 ID',
    `credited_score` INT NOT NULL COMMENT '本次点赞给每个记录标签加的分值',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '点赞时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content` (`user_id`, `content_id`),
    KEY `idx_content_id` (`content_id`),
    KEY `idx_user_create_time` (`user_id`, `create_time`)
) ENGINE=InnoDB COMMENT='内容点赞';

-- 作者获赞冗余。权威仍是 content_like，经作品赞数的落库和对账把差额记到这一行
CREATE TABLE IF NOT EXISTS `author_like_count` (
    `author_id`      BIGINT NOT NULL COMMENT '作者用户ID',
    `like_count`     BIGINT NOT NULL DEFAULT 0 COMMENT '获赞数',
    `version`        BIGINT NOT NULL DEFAULT 0 COMMENT '获赞数每变一次加一',
    `synced_version` BIGINT NOT NULL DEFAULT 0 COMMENT '已成功抄到 user.like_count 的版本',
    PRIMARY KEY (`author_id`)
) ENGINE=InnoDB COMMENT='作者获赞冗余';

-- 内容收藏表(视频/动态同池, 取消收藏即删行)
CREATE TABLE IF NOT EXISTS `content_collect` (
    `id`         BIGINT   NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`    BIGINT   NOT NULL COMMENT '收藏用户',
    `content_id` BIGINT   NOT NULL COMMENT '内容ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '收藏时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content` (`user_id`, `content_id`),
    KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB COMMENT='内容收藏';

-- 分享事实流水(把内容卡片分享进聊天会话的动作账本, append-only 不去重, 口径为分享动作次数)
CREATE TABLE IF NOT EXISTS `content_share` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`     BIGINT   NOT NULL COMMENT '分享用户',
    `content_id`  BIGINT   NOT NULL COMMENT '内容ID',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '分享时间',
    PRIMARY KEY (`id`),
    KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB COMMENT='内容分享';

-- 用户观看历史，每个用户和内容保留一行，含最近观看时间和续播位置。
CREATE TABLE IF NOT EXISTS `user_view_history` (
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`           BIGINT      NOT NULL COMMENT '观看用户',
    `content_id`        BIGINT      NOT NULL COMMENT '内容ID',
    `view_time`         DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '最近观看时间, upsert 时取较晚者',
    `created_at`        DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次观看时间, 行首次插入时生成, upsert 不改写',
    `position_ms`       BIGINT      NOT NULL DEFAULT 0 COMMENT '内容的续播位置（毫秒）',
    `reported_at`       BIGINT               DEFAULT NULL COMMENT '续播版本: 最近一次接受的快照接收时间(毫秒), NULL=未保存过位置',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content` (`user_id`, `content_id`),
    KEY `idx_user_view_time` (`user_id`, `view_time`)
) ENGINE=InnoDB COMMENT='用户浏览历史(含续播位置)';

-- 内容评论表
CREATE TABLE IF NOT EXISTS `content_comment` (
    `id`            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `content_id`    BIGINT       NOT NULL COMMENT '所属内容',
    `user_id`       BIGINT       NOT NULL COMMENT '评论人',
    `path`          VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '物化路径, 含自己: 根评论=自己id, 回复=父path.自己id',
    `level`         TINYINT      NOT NULL DEFAULT 1 COMMENT '1=根评论, >1为楼中楼回复',
    `reply_user_id` BIGINT                DEFAULT NULL COMMENT '被回复用户',
    `content`       TEXT         NOT NULL COMMENT '评论正文',
    `like_count`    INT          NOT NULL DEFAULT 0 COMMENT '评论获赞数',
    `reply_count`   INT          NOT NULL DEFAULT 0 COMMENT '回复数',
    `status`        VARCHAR(16)  NOT NULL DEFAULT 'visible' COMMENT 'visible/hidden/deleted/blocked',
    `create_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_content_level_time` (`content_id`, `level`, `create_time`),
    KEY `idx_content_level_hot` (`content_id`, `level`, `like_count`),
    KEY `idx_path` (`path`)
) ENGINE=InnoDB COMMENT='内容评论';

-- 评论点赞表(评论点赞关系的权威来源, 对账与幂等判重都靠它)
CREATE TABLE IF NOT EXISTS `content_comment_like` (
    `id`          BIGINT   NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`     BIGINT   NOT NULL COMMENT '点赞用户',
    `comment_id`  BIGINT   NOT NULL COMMENT '评论ID',
    `is_liked`    TINYINT  NOT NULL DEFAULT 1 COMMENT '1已点赞 0已取消',
    `create_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '首次点赞时间',
    `update_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近状态变更时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_comment` (`user_id`, `comment_id`),
    KEY `idx_comment_liked` (`comment_id`, `is_liked`)
) ENGINE=InnoDB COMMENT='评论点赞';

-- 站内通知表
CREATE TABLE IF NOT EXISTS `user_notification` (
    `id`          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `receiver_id` BIGINT       NOT NULL COMMENT '接收人',
    `sender_id`   BIGINT                DEFAULT NULL COMMENT '触发人, 系统通知为NULL',
    `type`        VARCHAR(32)  NOT NULL COMMENT 'COMMENT_LIKE/COMMENT_REPLY/CONTENT_COMMENT等',
    `title`       VARCHAR(128)          DEFAULT '' COMMENT '标题',
    `content`     VARCHAR(500)          DEFAULT '' COMMENT '摘要文案',
    `ref_type`    VARCHAR(16)           DEFAULT NULL COMMENT 'CONTENT/COMMENT/SYSTEM/CHAT',
    `ref_id`      BIGINT                DEFAULT NULL COMMENT '关联ID',
    `extra_json`  TEXT COMMENT '扩展字段JSON',
    `is_read`     TINYINT      NOT NULL DEFAULT 0 COMMENT '0未读 1已读',
    `create_time` DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (`id`),
    KEY `idx_receiver_time` (`receiver_id`, `create_time`)
) ENGINE=InnoDB COMMENT='站内通知';

-- 评论举报表
CREATE TABLE IF NOT EXISTS `comment_report` (
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `comment_id`       BIGINT       NOT NULL COMMENT '被举报评论',
    `reporter_user_id` BIGINT       NOT NULL COMMENT '举报人',
    `reason_code`      VARCHAR(16)  NOT NULL COMMENT '辱骂/涉黄/广告/引战/其他 的枚举名',
    `remark`           VARCHAR(255)          DEFAULT '' COMMENT '补充说明',
    `status`           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/ACCEPTED/REJECTED',
    `created_at`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '举报时间',
    PRIMARY KEY (`id`),
    KEY `idx_comment_id` (`comment_id`),
    KEY `idx_status` (`status`)
) ENGINE=InnoDB COMMENT='评论举报';

-- 标签表
CREATE TABLE IF NOT EXISTS `tag` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `name`       VARCHAR(64) NOT NULL COMMENT '展示名',
    `slug`       VARCHAR(64) NOT NULL COMMENT '唯一键, URL用',
    `category_code` VARCHAR(32)       DEFAULT NULL COMMENT '所属类目, null=通用',
    `status`     TINYINT     NOT NULL DEFAULT 1 COMMENT '1启用 0停用',
    `sort_order` INT         NOT NULL DEFAULT 0 COMMENT '越大越靠前',
    `created_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_slug` (`slug`),
    KEY `idx_category` (`category_code`)
) ENGINE=InnoDB COMMENT='标签';

-- 内容-标签关联表
CREATE TABLE IF NOT EXISTS `content_tag` (
    `id`         BIGINT        NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `content_id` BIGINT        NOT NULL COMMENT '内容ID',
    `tag_id`     BIGINT        NOT NULL COMMENT '标签ID',
    `weight`     DECIMAL(5, 2)          DEFAULT 1.00 COMMENT '排序权重',
    `created_at` DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关联时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_content_tag` (`content_id`, `tag_id`),
    KEY `idx_tag_id` (`tag_id`)
) ENGINE=InnoDB COMMENT='内容标签关联';

-- 用户标签偏好表(推荐用)
CREATE TABLE IF NOT EXISTS `user_tag_preference` (
    `id`         BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`    BIGINT      NOT NULL COMMENT '用户ID',
    `tag_id`     BIGINT      NOT NULL COMMENT '标签ID',
    `score`      INT         NOT NULL DEFAULT 0 COMMENT '偏好分, 越大越偏好',
    `source`     VARCHAR(16) NOT NULL DEFAULT 'behavior' COMMENT 'behavior/manual',
    `updated_at` DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近计分时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_tag` (`user_id`, `tag_id`)
) ENGINE=InnoDB COMMENT='用户标签偏好';

-- 用户-内容行为流水表(推荐用)
CREATE TABLE IF NOT EXISTS `user_video_behavior` (
    `id`          BIGINT      NOT NULL COMMENT '雪花ID',
    `user_id`     BIGINT      NOT NULL COMMENT '用户ID',
    `content_id`  BIGINT      NOT NULL COMMENT '内容ID',
    `session_id`  VARCHAR(64)          DEFAULT NULL COMMENT '播放行为按服务端会话去重，显式不感兴趣没有播放会话',
    `event_type`  VARCHAR(16) NOT NULL COMMENT 'play/finish/skip/dislike',
    `duration_ms` BIGINT               DEFAULT 0 COMMENT '停留/播放时长(毫秒)',
    `created_at`  DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '行为时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_content_session_event` (`user_id`, `content_id`, `session_id`, `event_type`),
    KEY `idx_user_time` (`user_id`, `created_at`),
    KEY `idx_content_id` (`content_id`)
) ENGINE=InnoDB COMMENT='用户视频行为流水';

-- 用户不感兴趣的作品，每条独立到期
CREATE TABLE IF NOT EXISTS `content_dislike` (
    `id`          BIGINT NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `user_id`     BIGINT NOT NULL COMMENT '用户ID',
    `content_id`  BIGINT NOT NULL COMMENT '不感兴趣的内容',
    `expires_at`  BIGINT NOT NULL COMMENT 'UTC 毫秒时间戳',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_dislike_user_content` (`user_id`, `content_id`),
    KEY `idx_dislike_user_expiry` (`user_id`, `expires_at`)
) ENGINE=InnoDB COMMENT='用户作品推荐冷却';

-- 物品相似度表(ItemCF/Swing 离线产物, 推荐用; item_a/item_b 双向各存一行, 按内容类型分池)
CREATE TABLE IF NOT EXISTS `item_similarity` (
    `id`           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增ID',
    `algorithm`    VARCHAR(16) NOT NULL COMMENT 'itemcf/swing',
    `content_type` VARCHAR(16) NOT NULL COMMENT '内容类型: video/dynamic/...',
    `item_a`       BIGINT      NOT NULL COMMENT '内容ID',
    `item_b`       BIGINT      NOT NULL COMMENT '邻居内容ID(与 item_a 同类型)',
    `score`        DOUBLE      NOT NULL COMMENT '相似度分',
    `updated_at`   DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最近计算时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_algo_pair` (`algorithm`, `item_a`, `item_b`),
    KEY `idx_algo_item` (`algorithm`, `item_a`, `score`)
) ENGINE=InnoDB COMMENT='物品相似度';

-- 音频库(动态/幻灯片合成共用; 用户上传自动入库, 默认公开, 可转私密)
CREATE TABLE IF NOT EXISTS `audio` (
    `id`                      BIGINT        NOT NULL COMMENT '雪花ID',
    `uploader_id`             BIGINT        NOT NULL COMMENT '上传者',
    `title`                   VARCHAR(100)  DEFAULT '' COMMENT '展示名',
    `request_id`              VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '客户端上传请求标识, 同一上传者内幂等',
    `request_fingerprint`     VARCHAR(64)   CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '上传参数指纹, 同请求重放时校验',
    `source_object_key`       VARCHAR(512)  NOT NULL COMMENT '待后台固定的上传原件',
    `source_cover_object_key` VARCHAR(512)  DEFAULT NULL COMMENT '待后台固定的封面原件',
    `upload_size`             BIGINT        NOT NULL COMMENT '声明的音频字节数',
    `upload_content_type`     VARCHAR(128)  NOT NULL COMMENT '声明的音频类型',
    `cover_upload_size`       BIGINT        DEFAULT NULL COMMENT '声明的封面字节数',
    `cover_upload_content_type` VARCHAR(128) DEFAULT NULL COMMENT '声明的封面类型',
    `upload_deadline`         DATETIME      NOT NULL COMMENT '直传截止时间',
    `upload_after`            DATETIME      DEFAULT NULL COMMENT '允许校验直传的时间, 领取后顺延作租约',
    `object_key`              VARCHAR(512)  DEFAULT NULL COMMENT '后台验证通过的不可变音频',
    `cover_object_key`        VARCHAR(512)  DEFAULT NULL COMMENT '仅供人工审核的不可变封面',
    `duration_ms`             INT           DEFAULT NULL COMMENT '服务端实际解码时长(毫秒)',
    `file_size`               BIGINT        DEFAULT NULL COMMENT '固定后的音频字节数',
    `mime`                    VARCHAR(64)   DEFAULT NULL COMMENT '固定后的媒体类型',
    `processing_status`       VARCHAR(16)   NOT NULL DEFAULT 'uploading' COMMENT 'uploading/processing/ready/failed/expired',
    `review_status`           VARCHAR(16)   NOT NULL DEFAULT 'pending' COMMENT 'pending/approved/rejected/banned',
    `preparation_attempt`     INT           NOT NULL DEFAULT 0 COMMENT '准备或分析尝试次数',
    `preparation_retry`       TINYINT       NOT NULL DEFAULT 0 COMMENT '1表示按用户重试执行',
    `preparation_after`       DATETIME      DEFAULT NULL COMMENT '允许开始准备或分析的时间, 领取后顺延作租约',
    `processing_deadline`     DATETIME      DEFAULT NULL COMMENT '本次处理截止时间',
    `error_message`           VARCHAR(500)  DEFAULT NULL COMMENT '最近一次处理失败原因',
    `analysis_status`         VARCHAR(24)   DEFAULT NULL COMMENT 'recognizing/screening/manual/failed, 未开始为空',
    `analysis_attempt`        INT           NOT NULL DEFAULT 0 COMMENT '分析尝试次数',
    `transcript_key`          VARCHAR(512)  DEFAULT NULL COMMENT '转写结果对象键',
    `machine_result_json`     MEDIUMTEXT COMMENT '机审结果',
    `review_reason`           VARCHAR(4000) DEFAULT NULL COMMENT '最近审核理由',
    `historical_author_count`             BIGINT        NOT NULL DEFAULT 0 COMMENT '异步维护的历史使用作者数，仅管理员可见',
    `index_version`           BIGINT        NOT NULL DEFAULT 1 COMMENT '搜索文档目标版本, 变更时加一',
    `indexed_version`         BIGINT        NOT NULL DEFAULT 0 COMMENT '搜索侧已确认的版本',
    `index_after`             DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '允许投递搜索索引的时间, 领取后顺延, 确认后清空',
    `index_pending_since`     DATETIME      DEFAULT CURRENT_TIMESTAMP COMMENT '当前未确认版本开始等待的时间, 确认后清空',
    `visibility`              VARCHAR(16)   NOT NULL DEFAULT 'public' COMMENT 'public/private',
    `create_time`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`             DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
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
    `id`           BIGINT        NOT NULL COMMENT '雪花ID',
    `audio_id`     BIGINT        NOT NULL COMMENT '音频ID',
    `source`       VARCHAR(16)   NOT NULL COMMENT 'AUTO/MANUAL/TECHNICAL',
    `decision`     VARCHAR(32)   NOT NULL COMMENT '审核结论, 如 PASS/REJECT/BAN/SCREENED/ERROR',
    `reason`       VARCHAR(4000) COMMENT '理由',
    `payload_json` MEDIUMTEXT COMMENT '机审或失败载荷',
    `attempt`      INT COMMENT '对应的分析尝试次数',
    `actor_id`     BIGINT COMMENT '人工操作者, 机器记录为空',
    `create_time`  DATETIME      NOT NULL COMMENT '记录时间',
    PRIMARY KEY (`id`),
    KEY `idx_audio_review` (`audio_id`, `create_time`, `id`)
) ENGINE=InnoDB COMMENT='音频独立审核记录';

-- 图集配乐动态(主体表; 时间轴/焦点等嵌套结构存 JSON 列)
CREATE TABLE IF NOT EXISTS `dynamic` (
    `id` BIGINT NOT NULL PRIMARY KEY COMMENT '与 content 相同',
    `media_json` JSON NOT NULL COMMENT '有序图片、画布、转场和可选原始音频快照，无签名URL'
) ENGINE=InnoDB COMMENT='原生图集媒体';

-- 可靠管线事件与业务状态同库、同事务记录。video_db 与 transcode_db 各一份。
CREATE TABLE IF NOT EXISTS pipeline_event_outbox (
    event_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL PRIMARY KEY COMMENT '事件标识',
    aggregate_type VARCHAR(16) NOT NULL COMMENT 'TASK/PIPELINE/DRAFT/CONTENT/RETRY_BATCH',
    aggregate_id VARCHAR(128) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '聚合根标识',
    event_sequence BIGINT NOT NULL COMMENT '同一聚合内从 1 递增',
    occurred_at DATETIME(6) NOT NULL COMMENT '事件发生时间, UTC',
    payload MEDIUMTEXT NOT NULL COMMENT '事件 JSON',
    payload_digest CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT 'payload 摘要',
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING/SENDING/SENT, 隔离后回到 PENDING',
    lease_token VARCHAR(64) COMMENT '当前投递租约',
    lease_until DATETIME(6) COMMENT '租约到期时间, UTC',
    sent_at DATETIME(6) COMMENT '投递成功时间, UTC, 七天后清理',
    quarantine_reason VARCHAR(128) COMMENT '隔离原因, 非空则不再投递',
    created_at DATETIME(6) NOT NULL COMMENT '写入时间, UTC',
    UNIQUE KEY uk_event_sequence(aggregate_type, aggregate_id, event_sequence),
    KEY idx_outbox_pending(status, quarantine_reason, created_at, event_id),
    KEY idx_outbox_lease(status, quarantine_reason, lease_until, event_id),
    KEY idx_outbox_cleanup(status, quarantine_reason, sent_at, event_id)
) ENGINE=InnoDB COMMENT='管线事件发件箱';

-- ==================== transcode_db: 转码库(transcode-svc) ====================
CREATE DATABASE IF NOT EXISTS transcode_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE transcode_db;

-- 列定义和表注释从 video_db.pipeline_event_outbox 复制
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
    `error_message`     VARCHAR(500)          DEFAULT NULL COMMENT '失败原因',
    `next_dispatch_at`  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '允许再次派发的时间',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    KEY `idx_key_status` (`task_key`, `status`),
    KEY `idx_dispatch` (`status`, `next_dispatch_at`, `id`),
    KEY `idx_create_time` (`create_time`)
) ENGINE=InnoDB COMMENT='动态成片转码任务';

-- ==================== video_db: 视频上传草稿 ====================
USE video_db;

-- 视频上传后转码 READY；图集原生素材校验后 READY；提交冻结为 submitted。
CREATE TABLE IF NOT EXISTS `media_draft` (
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已写入发件箱的最后事件序号',
    media_version BIGINT NOT NULL DEFAULT 0 COMMENT '媒体版本, 处理参数变化时加一',
    preparation_id BIGINT NOT NULL COMMENT '当前媒体准备单',
    preparation_attempt INT NOT NULL DEFAULT 0 COMMENT '当前准备单的尝试次数',
    preparation_retry TINYINT NOT NULL DEFAULT 0 COMMENT '1表示按用户重试执行',
    preparation_after DATETIME COMMENT '允许开始准备的时间, 领取后顺延作租约',
    submission_state VARCHAR(16) NOT NULL DEFAULT 'none' COMMENT '发布意图 none/pending/submitted/failed',
    submission_revision INT COMMENT '本次提交锁定的草稿修订号',
    submission_attempts INT NOT NULL DEFAULT 0 COMMENT '提交尝试次数',
    submission_after DATETIME COMMENT '提交租约, 到期后允许接管',
    submission_deadline DATETIME COMMENT '本次提交截止时间',
    submission_error VARCHAR(500) COMMENT '最近一次提交失败原因',
    KEY idx_draft_submission(submission_state, submission_after, id),
    KEY idx_draft_user_submission(user_id, submission_state, create_time, id),
    expires_at DATETIME NOT NULL COMMENT '草稿过期时间',
    KEY idx_draft_expiry(status, expires_at, id),
    prepared_json TEXT COMMENT '准备完成的媒体清单',
    prepared_cover_object_key VARCHAR(512) COMMENT '准备完成的封面对象键',
    `id`                BIGINT       NOT NULL COMMENT '雪花ID, 与后续video/content共用',
    `user_id`           BIGINT       NOT NULL COMMENT '属主',
    `type`              VARCHAR(16)  NOT NULL COMMENT 'video/dynamic',
    `title`             VARCHAR(100)          DEFAULT NULL COMMENT '标题',
    `description`       VARCHAR(512)          DEFAULT NULL COMMENT '简介',
    `visibility`        VARCHAR(16)  NOT NULL DEFAULT 'public' COMMENT 'public/private',
    `allow_comment`     TINYINT      NOT NULL DEFAULT 1 COMMENT '1允许评论 0关闭',
    `cover_object_key` VARCHAR(512) COMMENT '用户指定的封面对象键',
    `revision` INT NOT NULL DEFAULT 0 COMMENT '草稿修订号, 元数据或媒体变更时加一',
    `media_json`        JSON                 DEFAULT NULL COMMENT '原生图集媒体快照，无签名URL',
    `object_key`        VARCHAR(512)          DEFAULT NULL COMMENT '视频原件；图集为空',
    `original_filename` VARCHAR(256)          DEFAULT NULL COMMENT '上传时的原始文件名',
    `status`            VARCHAR(16)  NOT NULL DEFAULT 'uploading' COMMENT 'uploading/processing/ready/failed/expired/submitted',
    `duration_ms`       BIGINT                DEFAULT NULL COMMENT 'FFprobe 探测时长',
    `error_message`     VARCHAR(500)          DEFAULT NULL COMMENT '最近一次准备失败原因',
    `create_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_object_key` (`object_key`),
    KEY idx_draft_preparation(status, preparation_after, id),
    KEY `idx_user_status` (`user_id`, `status`)
) ENGINE=InnoDB COMMENT='内容草稿';

-- ==================== transcode_db: 媒体处理任务(DAG节点) ====================
USE transcode_db;

CREATE TABLE IF NOT EXISTS media_pipeline (
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已写入发件箱的最后事件序号',
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    content_id BIGINT NOT NULL COMMENT '来源内容',
    input_json JSON NOT NULL COMMENT '管线输入快照, 创建后不改',
    status VARCHAR(32) NOT NULL COMMENT 'PROCESSING/COMPLETED/WAITING_ARTIFACTS',
    received TINYINT NOT NULL DEFAULT 0 COMMENT '1表示视频侧已确认收下产物',
    manifest_json JSON COMMENT '完成时交给视频侧的产物清单',
    submitted_at DATETIME NOT NULL COMMENT '提交时间',
    completed_at DATETIME COMMENT '完成时间',
    notify_after DATETIME COMMENT '允许再次通知视频侧的时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_pipeline_content(content_id),
    KEY idx_pipeline_delivery(status, received, notify_after, id),
    KEY idx_pipeline_metrics_delivery(status, received, completed_at, id),
    KEY idx_pipeline_status(status, id)
) ENGINE=InnoDB COMMENT='媒体处理管线';
CREATE TABLE IF NOT EXISTS media_task (
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已写入发件箱的最后事件序号',
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    owner_id BIGINT NOT NULL COMMENT '阶段归属ID: 草稿、准备单或内容, 随 phase 变化',
    pipeline_id BIGINT COMMENT '所属媒体管线, 草稿阶段可空',
    retry_batch_id BIGINT COMMENT '人工重试批次, 不在批次中为空',
    failure_code VARCHAR(80) COMMENT '依赖不可用或终态失败码',
    dependency_service VARCHAR(16) COMMENT '阻塞任务的外部依赖 ASR/OCR/LLM',
    phase VARCHAR(16) NOT NULL COMMENT 'DRAFT/CONTENT/REVIEW',
    task_type VARCHAR(16) NOT NULL COMMENT 'TRANSCODE/PREPARE/AUDIO_PREPARE/AUDIO_ASR/AUDIO_REVIEW/COVER/ASR/OCR/SUMMARY/TAG/REVIEW',
    status VARCHAR(16) NOT NULL COMMENT 'PENDING/READY/RUNNING/RETRY_WAIT/SUCCESS/DEAD/BLOCKED',
    completion_ack TINYINT NOT NULL DEFAULT 0 COMMENT '1表示完成状态已被确认',
    attempt INT NOT NULL DEFAULT 0 COMMENT '已开始的执行次数',
    retry_count INT NOT NULL DEFAULT 0 COMMENT '自动重试次数',
    draft_input_json JSON COMMENT '创建时冻结的输入, 之后不改',
    dependencies_json JSON NOT NULL COMMENT '前置任务类型',
    result_json JSON COMMENT '任务产物',
    error_message VARCHAR(500) COMMENT '最近失败原因',
    ready_at DATETIME COMMENT '依赖满足、可以派发的时间',
    started_at DATETIME COMMENT '最近开始执行的时间',
    next_retry_at DATETIME COMMENT '下次自动重试时间',
    unavailable_deadline DATETIME COMMENT '依赖持续不可用时的放弃时间',
    dispatch_after DATETIME COMMENT '允许派发的时间, 领取后顺延作租约',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_owner_phase_type(owner_id, phase, task_type),
    UNIQUE KEY uk_pipeline_phase_type(pipeline_id, phase, task_type),
    KEY idx_task_metrics_ready(status, ready_at, id),
    KEY idx_task_batch(retry_batch_id, status, id),
    KEY idx_task_dispatch(status, dispatch_after, id),
    KEY idx_task_retry(status, next_retry_at, id),
    KEY idx_task_lease(status, update_time, id),
    KEY idx_task_delivery(phase, completion_ack, status, id)
) ENGINE=InnoDB COMMENT='媒体处理任务';

USE video_db;
CREATE TABLE IF NOT EXISTS content_understanding (
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已写入发件箱的最后事件序号',
    content_id BIGINT NOT NULL PRIMARY KEY COMMENT '内容ID, 与 content 相同',
    pipeline_id BIGINT NOT NULL COMMENT '媒体管线ID',
    state VARCHAR(32) NOT NULL DEFAULT 'WAITING_PIPELINE' COMMENT 'WAITING_PIPELINE/AUTO_PENDING/AUTO_RUNNING/MANUAL_PENDING/APPROVED/REJECTED/CANCELLED',
    input_json JSON NOT NULL COMMENT '提交时的内容与媒体快照',
    submission_ack TINYINT NOT NULL DEFAULT 0 COMMENT '1表示提交消息已发出',
    received TINYINT NOT NULL DEFAULT 0 COMMENT '1表示理解产物已收下',
    retry_requested TINYINT NOT NULL DEFAULT 0 COMMENT '1表示已请求重跑理解',
    subtitle_key VARCHAR(512) COMMENT '字幕对象键',
    segments_key VARCHAR(512) COMMENT '转写分段对象键',
    ocr_key VARCHAR(512) COMMENT 'OCR 产物对象键',
    summary_key VARCHAR(512) COMMENT '摘要产物对象键',
    tag_key VARCHAR(512) COMMENT '标签产物对象键',
    summary TEXT COMMENT '展示用摘要',
    chapters_json JSON COMMENT '章节列表',
    review_attempt INT NOT NULL DEFAULT 0 COMMENT '自动审核尝试次数',
    submitted_at DATETIME NOT NULL COMMENT '提交管线时间, UTC',
    received_at DATETIME COMMENT '收下产物时间, UTC',
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_understanding_pipeline(pipeline_id),
    KEY idx_understanding_state(state, content_id),
    KEY idx_understanding_submission(submission_ack, content_id),
    KEY idx_understanding_retry(retry_requested, content_id)
) ENGINE=InnoDB COMMENT='内容理解与审核进度';
CREATE TABLE IF NOT EXISTS content_review_record (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    content_id BIGINT NOT NULL COMMENT '内容ID',
    pipeline_id BIGINT COMMENT '媒体管线, 无管线的连带处置为空',
    attempt INT NOT NULL COMMENT '第几次审核',
    source VARCHAR(16) NOT NULL COMMENT 'AUTO/MANUAL/AUDIO',
    decision VARCHAR(16) NOT NULL COMMENT 'PASS/REJECT/UNCERTAIN/BAN/ERROR',
    risk_score DOUBLE DEFAULT NULL COMMENT '机审风险分',
    confidence DOUBLE DEFAULT NULL COMMENT '机审置信度',
    reason TEXT NOT NULL COMMENT '审核理由',
    evidence_json JSON COMMENT '审核证据',
    model VARCHAR(128) COMMENT '机审模型',
    rules_version VARCHAR(64) COMMENT '规则版本',
    actor_id BIGINT DEFAULT NULL COMMENT '人工审核人, 机器记录为空',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    KEY idx_review_content (content_id, create_time),
    UNIQUE KEY uk_review_attempt(pipeline_id, attempt, source, decision)
) ENGINE=InnoDB COMMENT='内容审核记录';
CREATE TABLE IF NOT EXISTS content_publication_event (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    content_id BIGINT NOT NULL COMMENT '内容ID',
    author_id BIGINT NOT NULL COMMENT '作者用户ID',
    status VARCHAR(16) NOT NULL COMMENT '当时的内容状态 review/published/banned',
    audio_id BIGINT DEFAULT NULL COMMENT '关联音频, 无配乐为空',
    audio_count_done TINYINT NOT NULL DEFAULT 1 COMMENT '1表示历史使用作者数已处理或无需处理',
    reason TEXT COMMENT '审核或变更理由',
    search_done TINYINT NOT NULL DEFAULT 0 COMMENT '1表示搜索文档已重建',
    notice_done TINYINT NOT NULL DEFAULT 0 COMMENT '1表示站内通知已处理',
    completed TINYINT NOT NULL DEFAULT 0 COMMENT '1表示搜索、通知和音频计数都已完成',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    KEY idx_publication_metrics_age (completed, create_time, id),
    KEY idx_publication_pending (completed, id),
    KEY idx_audio_count(audio_id, audio_count_done, id)
) ENGINE=InnoDB COMMENT='发布后的搜索、通知和音频作者数待办';
USE transcode_db;

CREATE TABLE IF NOT EXISTS media_preparation (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID, 由草稿侧分配',
    draft_id BIGINT NOT NULL COMMENT '所属草稿',
    media_version BIGINT NOT NULL COMMENT '对应草稿的媒体版本',
    user_id BIGINT NOT NULL COMMENT '属主',
    request_json TEXT NOT NULL COMMENT '准备请求快照',
    parameter_version VARCHAR(64) NOT NULL COMMENT '抽帧等处理参数指纹',
    lifecycle VARCHAR(16) NOT NULL DEFAULT 'ACTIVE' COMMENT 'ACTIVE/RETIRED',
    reclaim_after DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '允许回收产物的时间',
    retired_at DATETIME COMMENT '退役时间',
    KEY idx_preparation_reclaim(reclaim_after, id),
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_preparation_draft_version(draft_id, media_version)
) COMMENT='草稿媒体准备';
CREATE TABLE IF NOT EXISTS media_preparation_item (
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    preparation_id BIGINT NOT NULL COMMENT '所属准备单',
    object_owner_id BIGINT NOT NULL COMMENT '对象的物理属主准备单, 复用时与 preparation_id 不同',
    role VARCHAR(16) NOT NULL COMMENT 'VIDEO/AUDIO/IMAGE/FRAME/OCR_IMAGE/HLS_MEDIA/HLS_PLAYLIST',
    item_index INT NOT NULL COMMENT '同角色内序号, HLS 用清晰度代码',
    object_key VARCHAR(512) NOT NULL COMMENT '产物对象键',
    source_json TEXT COMMENT '源对象快照',
    result_json MEDIUMTEXT COMMENT '处理结果',
    status VARCHAR(16) NOT NULL COMMENT 'ALLOCATED/PLANNED/READY/DELETING/DELETED',
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    UNIQUE KEY uk_preparation_item(preparation_id, role, item_index),
    KEY idx_preparation_object(object_key, preparation_id)
) COMMENT='准备产物登记';

USE transcode_db;
CREATE TABLE IF NOT EXISTS media_retry_batch (
    event_sequence BIGINT NOT NULL DEFAULT 0 COMMENT '已写入发件箱的最后事件序号',
    id BIGINT NOT NULL PRIMARY KEY COMMENT '雪花ID',
    request_id VARCHAR(128) NOT NULL COMMENT '调用方幂等键',
    actor_id BIGINT NOT NULL COMMENT '发起人',
    status VARCHAR(32) NOT NULL COMMENT 'QUEUED/RUNNING/COMPLETED/COMPLETED_WITH_FAILURES',
    active TINYINT COMMENT '1表示当前唯一活跃批次, 结束后置空',
    selection_json TEXT NOT NULL COMMENT '选中的任务及跳过原因',
    result_json MEDIUMTEXT COMMENT '批次执行结果',
    max_concurrency INT NOT NULL COMMENT '最大并发',
    created_at DATETIME NOT NULL COMMENT '创建时间',
    completed_at DATETIME COMMENT '完成时间',
    UNIQUE KEY uk_retry_request(request_id),
    UNIQUE KEY uk_retry_active(active)
) COMMENT='失败媒体任务的人工重试批次';

-- ==================== search_db: 热搜历史 ====================
CREATE DATABASE IF NOT EXISTS search_db DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin;
USE search_db;

CREATE TABLE IF NOT EXISTS hot_search_event (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY COMMENT '自增ID',
    event_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '采集事件标识, 幂等键',
    stat_date DATE NOT NULL COMMENT '归档日期, 上海时区; 已收尾日期的新事件顺延到下一天',
    happened_at BIGINT NOT NULL COMMENT '真实采集时间，UTC epoch 毫秒',
    event_hour BIGINT NOT NULL COMMENT '原始事件小时编号，不随归档日期顺延',
    user_id BIGINT NOT NULL COMMENT '搜索用户',
    raw_keyword TEXT NOT NULL COMMENT '用户原始搜索词',
    grouping_keyword VARCHAR(64) NOT NULL COMMENT '用于聚类的搜索词',
    group_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '热搜组标识',
    group_name VARCHAR(64) NOT NULL COMMENT '热搜组展示名',
    vector_version VARCHAR(255) NOT NULL COMMENT '聚类使用的向量模型版本',
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '落库时间',
    UNIQUE KEY uk_hot_search_event(event_id),
    KEY idx_hot_search_event_group(stat_date, group_id, happened_at, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='热搜原始事件';

CREATE TABLE IF NOT EXISTS hot_search_daily_group (
    stat_date DATE NOT NULL COMMENT '归档日期, 上海时区',
    group_id VARCHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '热搜组标识',
    group_name VARCHAR(64) NOT NULL COMMENT '热搜组展示名',
    vector_version VARCHAR(255) NOT NULL COMMENT '聚类使用的向量模型版本',
    search_count BIGINT UNSIGNED NOT NULL COMMENT '当日搜索次数',
    user_count BIGINT UNSIGNED NOT NULL COMMENT '当日去重用户数',
    score BIGINT UNSIGNED NOT NULL COMMENT '热度, 按用户和原始事件小时去重',
    rank_no BIGINT UNSIGNED NOT NULL COMMENT '当日名次, 从 1 起, 热度相同按 group_id',
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '最近重算时间',
    PRIMARY KEY (stat_date, group_id),
    KEY idx_hot_search_daily_rank(stat_date, rank_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_bin COMMENT='热搜按日汇总, 当日整表重算替换';
