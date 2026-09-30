package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.common.counter.CounterBuffer;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.support.SFunction;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserPort 本地实现
 */
@Component
@RequiredArgsConstructor
public class UserPortAdapter implements UserPort {

    private final cn.diaojiahong.myyoutube.user.service.IUserService userService;
    private final CounterBuffer counterBuffer;

    @Override
    public UserSummary getUser(Long id) {
        User user = userService.getById(id);
        return user == null ? null : toSummary(user);
    }

    @Override
    public List<UserSummary> listByIds(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return userService.listByIds(ids).stream().map(UserPortAdapter::toSummary).collect(Collectors.toList());
    }

    @Override
    public Map<Long, String> getNicknames(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, u -> u.getNickname() != null ? u.getNickname() : ""));
    }

    @Override
    public Map<Long, String> getAvatars(Collection<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }
        return userService.listByIds(ids).stream()
                .collect(Collectors.toMap(User::getId, User::getAvatarUrl));
    }

    @Override
    public void adjustCounters(List<CounterAdjust> adjustments) {
        for (CounterAdjust a : adjustments) {
            switch (a.getField()) {
                // 粉丝数/关注数只累加到 Redis, 由 user 模块的落库任务合并成一次 UPDATE。
                // 大 V 的单行竞争极高, 而这两个字段本来就不是同步可见的
                case "fans" -> counterBuffer.add(CounterType.USER_FANS, a.getUserId(), a.getDelta());
                case "attentionCount" -> counterBuffer.add(CounterType.USER_ATTENTION, a.getUserId(), a.getDelta());
                case "videoCount" -> adjustOne(a, User::getVideoCount);
                default -> throw new IllegalArgumentException("未知计数字段: " + a.getField());
            }
        }
    }

    /** 低频计数(发布数等)不值得缓冲, 直接落库 */
    private void adjustOne(CounterAdjust a, SFunction<User, ?> column) {
        LambdaUpdateChainWrapper<User> w = new LambdaUpdateChainWrapper<>(userService.getBaseMapper())
                .eq(User::getId, a.getUserId());
        int delta = Math.abs(a.getDelta());
        if (a.getDelta() < 0) {
            w.ge(column, delta).setDecrBy(column, delta);
        } else {
            w.setIncrBy(column, delta);
        }
        w.update();
    }

    private static UserSummary toSummary(User user) {
        UserSummary s = new UserSummary();
        BeanUtil.copyProperties(user, s);
        return s;
    }
}
