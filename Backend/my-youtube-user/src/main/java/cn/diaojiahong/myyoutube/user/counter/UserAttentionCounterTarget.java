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
 * user.attention_count 的落库目标。与 {@link UserFansCounterTarget} 同理,
 * 权威值来自 video_db 的 user_follow。
 */
@Component
@RequiredArgsConstructor
public class UserAttentionCounterTarget implements CounterTarget {

    private final IUserService userService;
    private final FollowPort followPort;

    @Override
    public CounterType type() {
        return CounterType.USER_ATTENTION;
    }

    @Override
    public void applyDelta(long userId, long delta) {
        LambdaUpdateChainWrapper<User> wrapper = userService.lambdaUpdate()
                .eq(User::getId, userId)
                .setIncrBy(User::getAttentionCount, delta);
        if (delta < 0) {
            wrapper.ge(User::getAttentionCount, -delta);
        }
        wrapper.update();
    }

    @Override
    public Long authoritativeValue(long userId) {
        return followPort.attentionCount(userId);
    }

    @Override
    public void overwrite(long userId, long value) {
        userService.lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getAttentionCount, value)
                .update();
    }
}
