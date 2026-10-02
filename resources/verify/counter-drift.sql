-- 全量对账探针
-- 对每个 id 算 COUNT(*) 权威值, 与 DB 当前值求差, 输出差值的分布。
-- 开发阶段(存量账≈0)差值应全为 0, 证明增量路径正确; 生产阶段可低频定期跑, 作为漂移体检。
--
-- 用法: docker exec -i myyoutube-mysql mysql -uroot -p*** < resources/verify/counter-drift.sql

-- content.like_count  vs  COUNT(content_like WHERE is_liked=1)
SELECT 'content.like_count' AS counter,
       COUNT(*)                          AS rows_total,
       COALESCE(SUM(diff = 0), 0)        AS diff_zero,
       COALESCE(SUM(diff > 0), 0)        AS db_higher,
       COALESCE(SUM(diff < 0), 0)        AS db_lower,
       COALESCE(MIN(diff), 0)            AS min_diff,
       COALESCE(MAX(diff), 0)            AS max_diff
FROM (SELECT c.id, c.like_count - COALESCE(l.cnt, 0) AS diff
      FROM video_db.content c
               LEFT JOIN (SELECT content_id, COUNT(*) AS cnt
                          FROM video_db.content_like
                          WHERE is_liked = 1
                          GROUP BY content_id) l ON l.content_id = c.id) t

UNION ALL

-- comment.like_count vs COUNT(content_comment_like WHERE is_liked=1)
SELECT 'comment.like_count',
       COUNT(*),
       COALESCE(SUM(diff = 0), 0),
       COALESCE(SUM(diff > 0), 0),
       COALESCE(SUM(diff < 0), 0),
       COALESCE(MIN(diff), 0),
       COALESCE(MAX(diff), 0)
FROM (SELECT c.id, c.like_count - COALESCE(cl.cnt, 0) AS diff
      FROM video_db.content_comment c
               LEFT JOIN (SELECT comment_id, COUNT(*) AS cnt
                          FROM video_db.content_comment_like
                          WHERE is_liked = 1
                          GROUP BY comment_id) cl ON cl.comment_id = c.id) t

UNION ALL

-- user.fans vs COUNT(user_follow WHERE followee_id=u.id AND is_deleted=0)
SELECT 'user.fans',
       COUNT(*),
       COALESCE(SUM(diff = 0), 0),
       COALESCE(SUM(diff > 0), 0),
       COALESCE(SUM(diff < 0), 0),
       COALESCE(MIN(diff), 0),
       COALESCE(MAX(diff), 0)
FROM (SELECT u.id, u.fans - COALESCE(f.cnt, 0) AS diff
      FROM user_db.user u
               LEFT JOIN (SELECT followee_id, COUNT(*) AS cnt
                          FROM video_db.user_follow
                          WHERE is_deleted = 0
                          GROUP BY followee_id) f ON f.followee_id = u.id) t

UNION ALL

-- user.attention_count vs COUNT(user_follow WHERE follower_id=u.id AND is_deleted=0)
SELECT 'user.attention_count',
       COUNT(*),
       COALESCE(SUM(diff = 0), 0),
       COALESCE(SUM(diff > 0), 0),
       COALESCE(SUM(diff < 0), 0),
       COALESCE(MIN(diff), 0),
       COALESCE(MAX(diff), 0)
FROM (SELECT u.id, u.attention_count - COALESCE(f.cnt, 0) AS diff
      FROM user_db.user u
               LEFT JOIN (SELECT follower_id, COUNT(*) AS cnt
                          FROM video_db.user_follow
                          WHERE is_deleted = 0
                          GROUP BY follower_id) f ON f.follower_id = u.id) t;

-- 只看偏差明细(排查用, 与上面的汇总二选一)
-- SELECT * FROM (
--     SELECT 'content.like_count' AS counter, c.id AS ref_id, c.like_count AS db_value, COALESCE(l.cnt, 0) AS truth
--     FROM video_db.content c
--              LEFT JOIN (SELECT content_id, COUNT(*) cnt FROM video_db.content_like WHERE is_liked = 1 GROUP BY content_id) l
--                        ON l.content_id = c.id
-- ) t WHERE db_value <> truth;
