package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import org.springframework.stereotype.Component;

/**
 * user.attention_count 的落库目标。权威值来自 video_db 的 user_follow, 经 FollowPort 获取。
 */
@Component
public class UserAttentionCounterTarget extends AbstractCounterTarget<User> {

    private final FollowPort followPort;

    public UserAttentionCounterTarget(IUserService userService, FollowPort followPort) {
        super(userService, User::getId, User::getAttentionCount);
        this.followPort = followPort;
    }

    @Override
    public CounterType type() {
        return CounterType.USER_ATTENTION;
    }

    @Override
    public Long authoritativeValue(long userId) {
        return followPort.attentionCount(userId);
    }
}
