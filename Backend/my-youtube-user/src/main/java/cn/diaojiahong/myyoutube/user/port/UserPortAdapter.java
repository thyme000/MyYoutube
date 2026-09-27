package cn.diaojiahong.myyoutube.user.port;

import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.conditions.update.LambdaUpdateChainWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * UserPort 本地实现: 过渡期旧单体与 user 服务共用, 代理到本模块 IUserService
 */
@Component
@RequiredArgsConstructor
public class UserPortAdapter implements UserPort {

    private final cn.diaojiahong.myyoutube.user.service.IUserService userService;

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
            LambdaUpdateChainWrapper<User> w = new LambdaUpdateChainWrapper<>(userService.getBaseMapper())
                    .eq(User::getId, a.getUserId());
            boolean decrease = a.getDelta() < 0;
            Integer delta = Math.abs(a.getDelta());
            switch (a.getField()) {
                case "fans" -> {
                    if (decrease) w.gt(User::getFans, 0).setDecrBy(User::getFans, delta);
                    else w.setIncrBy(User::getFans, delta);
                }
                case "attentionCount" -> {
                    if (decrease) w.gt(User::getAttentionCount, 0).setDecrBy(User::getAttentionCount, delta);
                    else w.setIncrBy(User::getAttentionCount, delta);
                }
                case "likeCount" -> {
                    if (decrease) w.gt(User::getLikeCount, 0).setDecrBy(User::getLikeCount, delta);
                    else w.setIncrBy(User::getLikeCount, delta);
                }
                case "videoCount" -> {
                    if (decrease) w.gt(User::getVideoCount, 0).setDecrBy(User::getVideoCount, delta);
                    else w.setIncrBy(User::getVideoCount, delta);
                }
                default -> throw new IllegalArgumentException("未知计数字段: " + a.getField());
            }
            w.update();
        }
    }

    private static UserSummary toSummary(User user) {
        UserSummary s = new UserSummary();
        BeanUtil.copyProperties(user, s);
        return s;
    }
}
