package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.CounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.diaojiahong.myyoutube.video.api.FollowPort;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * user.fans 的落库目标。写侧在 video 模块的 UserFollowServiceImpl, 落库侧在这里 ——
 * 权威值来自 video_db 的 user_follow, 只能经 FollowPort 问 video 服务要。
 */
@Component
@RequiredArgsConstructor
public class UserFansCounterTarget implements CounterTarget {

    private final IUserService userService;
    private final FollowPort followPort;

    @Override
    public CounterType type() {
        return CounterType.USER_FANS;
    }

    @Override
    public void applyDelta(long userId, long delta) {
        LambdaUpdateChainWrapper<User> wrapper = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .setIncrBy(User::getFans, delta);
        if (delta < 0) {
            wrapper.ge(User::getFans, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long userId) {
        return followPort.fansCount(userId);
    }

    @Override
    public void overwrite(long userId, long value) {
        userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getFans, value)
                .update();
    }
}
