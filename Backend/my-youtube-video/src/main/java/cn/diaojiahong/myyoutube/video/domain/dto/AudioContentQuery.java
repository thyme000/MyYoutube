package cn.diaojiahong.myyoutube.video.domain.dto;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AudioContentQuery {
    @Schema(description = "关联内容排序", allowableValues = {"latest", "mostLiked"}, defaultValue = "latest")
    private String sort;

    @Schema(description = "上一页返回的 nextCursor，首次请求省略")
    private String cursor;

    @Schema(description = "每次加载条数", defaultValue = "10")
    private int pageSize = 10;

    public ContentSortEnum order() {
        PageQuery.validate(1, pageSize);
        if (sort == null || sort.isBlank()) return ContentSortEnum.LATEST;
        ContentSortEnum order = ContentSortEnum.fromField(sort);
        if (order != ContentSortEnum.LATEST && order != ContentSortEnum.MOST_LIKED)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频关联内容仅支持最新或最多点赞排序");
        return order;
    }
}
