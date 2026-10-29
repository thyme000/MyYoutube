package cn.diaojiahong.myyoutube.content.domain.dto;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.CursorPageQuery;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class AuthorContentCursorQuery extends CursorPageQuery {
    @Schema(description = "内容类型，不传则查询全部类型")
    private String type;

    public ContentTypeEnum mediaType() {
        validate();
        ContentTypeEnum result = type == null ? null : ContentTypeEnum.fromCode(type);
        if (type != null && result == null) throw new BizException(ErrorCode.INVALID_ARGUMENT, "内容类型无效");
        type = result == null ? null : result.getCode();
        return result;
    }
}
