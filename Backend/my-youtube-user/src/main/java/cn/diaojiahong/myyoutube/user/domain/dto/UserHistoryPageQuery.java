package cn.diaojiahong.myyoutube.user.domain.dto;

import cn.diaojiahong.myyoutube.common.page.PageQuery;
import lombok.Data;

@Data
public class UserHistoryPageQuery extends PageQuery {
    private Long userId;
}
