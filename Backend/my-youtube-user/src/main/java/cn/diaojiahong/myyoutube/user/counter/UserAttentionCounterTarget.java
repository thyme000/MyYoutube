package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import org.springframework.stereotype.Component;

/**
 * user.attention_count 的落库目标。写侧与权威值同库: user_follow 在 user_db, COUNT 直查即可。
 */
@Component
public class UserAttentionCounterTarget extends AbstractCounterTarget<User> {

    private final IUserFollowService userFollowService;

    public UserAttentionCounterTarget(IUserService userService, IUserFollowService userFollowService) {
        super(userService, User::getId, User::getAttentionCount);
        this.userFollowService = userFollowService;
    }

    @Override
    public CounterType type() {
        return CounterType.USER_ATTENTION;
    }

    @Override
    public Long authoritativeValue(long userId) {
        return userFollowService.countAttention(userId);
    }
}
