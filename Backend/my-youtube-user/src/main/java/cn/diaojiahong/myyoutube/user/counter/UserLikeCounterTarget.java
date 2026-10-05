package cn.diaojiahong.myyoutube.user.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.counter.CounterType;
import cn.diaojiahong.myyoutube.search.api.UserDocPort;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * user.like_count 的落库目标。权威值要跨库聚合(video_db SUM), 不做对账, 只走增量路径;
 * 整轮 ack 后把增量聚合一次批量同步 ES 用户文档的获赞数, 失败不回滚落库(ES 漂移可容忍,
 * 不让它把 DB 增量变成重放双计)。
 */
@Component
public class UserLikeCounterTarget extends AbstractCounterTarget<User> {

    private final UserDocPort userDocPort;

    public UserLikeCounterTarget(IUserService userService, UserDocPort userDocPort) {
        super(userService, User::getId, User::getLikeCount);
        this.userDocPort = userDocPort;
    }

    @Override
    public CounterType type() {
        return CounterType.USER_LIKE;
    }

    @Override
    public Long authoritativeValue(long userId) {
        // 无可信权威来源, 对账跳过
        return null;
    }

    @Override
    public void afterFlush(Map<Long, Long> ackedDeltas) {
        List<UserDocPort.LikeCountSync> syncs = ackedDeltas.entrySet().stream()
                .map(e -> new UserDocPort.LikeCountSync(e.getKey(), e.getValue()))
                .toList();
        userDocPort.adjustLikeCountBatch(syncs);
    }
}
