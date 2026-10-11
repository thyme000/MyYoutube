package cn.diaojiahong.myyoutube.admin.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class AdminContentPageQueryDTO extends PageQuery {
    private String categoryCode;
    private String statusCode;
    private String type;
    private Long userId;
}
