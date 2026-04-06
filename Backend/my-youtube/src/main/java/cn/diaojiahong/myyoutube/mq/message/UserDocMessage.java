package cn.diaojiahong.myyoutube.mq.message;

import cn.diaojiahong.myyoutube.user.domain.po.User;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class UserDocMessage {
    private User user;
}
