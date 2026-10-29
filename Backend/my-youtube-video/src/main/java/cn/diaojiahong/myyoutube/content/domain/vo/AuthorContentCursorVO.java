package cn.diaojiahong.myyoutube.content.domain.vo;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import io.swagger.v3.oas.annotations.media.Schema;

public record AuthorContentCursorVO(
        CursorPage<ContentFeedVO, String> page,
        @Schema(description = "每次请求返回整个查询范围的展示数量，由本地作品基数减去受限音频引用数得到，与本次作品及音频过滤共享快照；不受游标位置影响，不用于判断是否还有下一批")
        long displayCount) {}
