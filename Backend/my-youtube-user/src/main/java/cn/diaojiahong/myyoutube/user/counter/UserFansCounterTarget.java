package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import org.springframework.stereotype.Component;

/**
 * user.fans 的落库目标。写侧在 video 模块的 UserFollowServiceImpl, 落库侧在这里 ——
 * 权威值来自 video_db 的 user_follow, 只能经 FollowPort 问 video 服务要。
 */
@Component
public class UserFansCounterTarget extends AbstractCounterTarget<User> {

    private final FollowPort followPort;

    public UserFansCounterTarget(IUserService userService, FollowPort followPort) {
        super(userService, User::getId, User::getFans);
        this.followPort = followPort;
    }

    @Override
    public CounterType type() {
        return CounterType.USER_FANS;
    }

    @Override
    public Long authoritativeValue(long userId) {
        return followPort.fansCount(userId);
    }
}
