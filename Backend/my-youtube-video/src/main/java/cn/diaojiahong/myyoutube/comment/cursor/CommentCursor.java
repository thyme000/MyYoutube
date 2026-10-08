package cn.diaojiahong.myyoutube.comment.cursor;

import lombok.Getter;

/**
 * 游标「排序值.评论id」: latest 的排序值是创建时间秒, hot 是点赞数。
 * 绑的是排序字段而非缓存, 缓存榜与 DB keyset 两界共用同一把钥匙。
 */
@Getter
public class CommentCursor {

    private final long score;
    private final long id;

    private CommentCursor(long score, long id) {
        this.score = score;
        this.id = id;
    }

    public static CommentCursor of(long score, long id) {
        return new CommentCursor(score, id);
    }

    public static CommentCursor decode(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return null;
        }
        int idx = cursor.lastIndexOf('.');
        if (idx <= 0 || idx == cursor.length() - 1) {
            throw new IllegalArgumentException("游标格式错误");
        }
        try {
            return new CommentCursor(Long.parseLong(cursor.substring(0, idx)),
                    Long.parseLong(cursor.substring(idx + 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("游标格式错误");
        }
    }

    public String encode() {
        return score + "." + id;
    }
}
