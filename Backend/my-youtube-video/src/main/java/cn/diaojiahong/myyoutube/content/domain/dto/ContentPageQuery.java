package cn.diaojiahong.myyoutube.content.domain.dto;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class ContentPageQuery extends PageQuery {
    private String type;

    public ContentTypeEnum mediaType() {
        ContentTypeEnum result = type == null ? null : ContentTypeEnum.fromCode(type);
        if (type != null && result == null)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "内容类型无效");
        validate();
        if ((long) (getPageNo() - 1) * getPageSize() > Integer.MAX_VALUE)
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "分页参数无效");
        type = result == null ? null : result.getCode();
        return result;
    }
}
