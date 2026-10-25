-- 已有开发数据库补列；新建数据库使用 schema.sql。
ALTER TABLE `content`
    ADD COLUMN `index_version` BIGINT NOT NULL DEFAULT 1 COMMENT '内容索引快照版本，与审核锁独立';
