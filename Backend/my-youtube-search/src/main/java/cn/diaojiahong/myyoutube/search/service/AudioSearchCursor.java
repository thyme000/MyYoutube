package cn.diaojiahong.myyoutube.search.service;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.Query;
import cn.diaojiahong.myyoutube.common.page.CursorCodec;

import java.util.Objects;

/** 音频搜索的续查位置。绑定关键词和归属范围，排序位置是创建时间与 id。 */
final class AudioSearchCursor {

    private static final CursorCodec<Position> CODEC = new CursorCodec<>(
            Position.class, 512, "音频搜索游标无效，请重新搜索");

    private AudioSearchCursor() {
    }

    static String keywordOf(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        return keyword.trim();
    }

    static String encode(Query request, long createdAt, long id) {
        return CODEC.encode(new Position(keywordOf(request.keyword()), request.uploaderId(), createdAt, id));
    }

    static Position decode(Query request) {
        String keyword = keywordOf(request.keyword());
        return CODEC.decode(request.cursor(), decoded -> {
            if (!Objects.equals(decoded.keyword(), keyword)
                    || !Objects.equals(decoded.uploaderId(), request.uploaderId())
                    || decoded.id() < 1
                    || decoded.createdAt() < 0) {
                throw new IllegalArgumentException();
            }
        });
    }

    record Position(String keyword, Long uploaderId, long createdAt, long id) {
    }
}
