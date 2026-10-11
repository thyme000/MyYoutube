package cn.diaojiahong.myyoutube.content.domain.dto;

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
        var result = type == null ? null : ContentTypeEnum.fromCode(type);
        if (type != null && result == null)
            throw new BizException(BizException.BIZ_ERROR, "内容类型无效");
        if (getPageNo() < 1 || getPageSize() < 1 || getPageSize() > 100
                || (long) (getPageNo() - 1) * getPageSize() > Integer.MAX_VALUE)
            throw new BizException(BizException.BIZ_ERROR, "分页参数无效");
        type = result == null ? null : result.getCode();
        return result;
    }
}
