package cn.diaojiahong.myyoutube.common.media;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import java.util.List;

public final class AudioDiscoveryContracts {
    private AudioDiscoveryContracts() {
    }

    public record Document(Long id, Long uploaderId, String title, String processingStatus, String reviewStatus,
                           String visibility, boolean deleted, long version, long createdAt) {
    }

    public record Query(String keyword, Long uploaderId, String cursor, int pageSize) {
        public void validate() {
            PageQuery.validatePageSize(pageSize);
            if (keyword != null && keyword.length() > 100 || uploaderId != null && uploaderId <= 0)
                throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频搜索参数超出范围");
        }
    }

    public record Slice(List<Long> ids, String nextCursor) {
    }

    public record Sync(Long audioId) {
        public static final String TAG = "audio";
    }

    public record Ack(Long audioId, long version) {
    }
}
