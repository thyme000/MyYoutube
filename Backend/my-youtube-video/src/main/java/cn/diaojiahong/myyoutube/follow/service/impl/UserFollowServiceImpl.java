package cn.diaojiahong.myyoutube.follow.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserRelationshipVO;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.common.mq.EsSyncPublisher;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.CounterAdjust;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * <p>
 * 用户关注关系表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-04-01
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserFollowServiceImpl extends ServiceImpl<UserFollowMapper, UserFollow> implements IUserFollowService {

    private final UserPort userPort;
    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;
    private final EsSyncPublisher esSyncPublisher;

    private static final long STORAGE_READ_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    @Override
    public void follow(Long authorId) {
        Long userId = getUserId();
        if (userId.equals(authorId)) {

            throw new BizException(BizException.USER_ERROR, "不能关注自己");
        }
        // 存在就修改,不存在就新增; 单条 UPDATE 走自动提交, 不开事务圈住下面的远程调用
        long id = IdWorker.getId();
        int row = baseMapper.followOrRestore(id, userId, authorId);
        // 关注成功后，更新用户信息 -> 被关注者粉丝数加一,关注者关注数量加一
        if (row > 0) {

            adjustCountersQuietly(List.of(
                    new CounterAdjust(authorId, "fans", 1),
                    new CounterAdjust(userId, "attentionCount", 1)));
        }
        // 延迟同步 ES 用户文档, 短时间多次变更天然合并(消费端以 MySQL 为准)
        esSyncPublisher.syncUserDoc(authorId);
        return;
    }

    /** 计数器尽力而为: 关注关系已落库, 远程计数失败只记日志, 不把 HTTP 调用拖进 DB 连接持有期 */
    private void adjustCountersQuietly(List<CounterAdjust> adjustments) {

        try {
            userPort.adjustCounters(adjustments);
        } catch (Exception e) {
            log.error("关注计数同步失败: {}", adjustments, e);
        }
    }

    private Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }

    @Override
    public void cancelFollow(Long authorId) {
        Long userId = getUserId();
        // 取消关注
        int row = baseMapper.cancelFollow(userId, authorId);
        // 更新用户信息 -> 被关注者粉丝数减一,关注者关注数量减一
        if (row > 0) {

            adjustCountersQuietly(List.of(
                    new CounterAdjust(authorId, "fans", -1),
                    new CounterAdjust(userId, "attentionCount", -1)));
        }
        // 延迟同步 ES 用户文档
        esSyncPublisher.syncUserDoc(authorId);
        return;
    }

    @Override
    public PageResult<UserRelationshipVO> getUserFollowList(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<UserFollow> page = lambdaQuery().eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getCreateTime)
                .page(pageQuery.toPage());
        List<UserFollow> follows = page.getRecords();
        if (follows == null || follows.isEmpty()) {

            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }
        List<Long> list = follows.stream().map(UserFollow::getFolloweeId).toList();

        List<UserRelationshipVO> voList = getUserRelationshipVOS(list);

        return PageResult.of(page.getTotal(), page.getPages(), voList);
    }

    @Override
    public Boolean isFollow(Long authorId) {
        Long userId = getUserId();
        UserFollow one = lambdaQuery()
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getFolloweeId, authorId).one();
        return one != null && !one.getIsDeleted();
    }

    @Override
    public Boolean interrelated(Long userId, List<Long> memberIds) {

        int expectedCount = (int) memberIds.stream().distinct().count();
        return baseMapper.interrelated(userId, memberIds, expectedCount);
    }

    @Override
    public Long countFans(Long followeeId) {

        return lambdaQuery()
                .eq(UserFollow::getFolloweeId, followeeId)
                .eq(UserFollow::getIsDeleted, false)
                .count();
    }

    @Override
    public Long countAttention(Long followerId) {

        return lambdaQuery()
                .eq(UserFollow::getFollowerId, followerId)
                .eq(UserFollow::getIsDeleted, false)
                .count();
    }

    @Override
    public PageResult<UserRelationshipVO> getFansList(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<UserFollow> page = lambdaQuery().eq(UserFollow::getFolloweeId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getCreateTime)
                .page(pageQuery.toPage());
        List<UserFollow> follows = page.getRecords();
        if (follows == null || follows.isEmpty()) {

            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }
        List<Long> list = follows.stream().map(UserFollow::getFollowerId).toList();
        List<UserRelationshipVO> voList = getUserRelationshipVOS(list);
        return PageResult.of(page.getTotal(), page.getPages(), voList);
    }

    @Override
    public List<Long> getFollowIds(Long userId) {

        return lambdaQuery()
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getUpdateTime)
                .last("LIMIT 200")
                .select(UserFollow::getFolloweeId)
                .list().stream().map(UserFollow::getFolloweeId).toList();
    }

    @Override
    public List<Boolean> isFollowOnList(List<Long> authorIds, Long userId) {

        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        if (authorIds == null || authorIds.isEmpty()) {

            return List.of();
        }
        Map<Long, Boolean> collect = lambdaQuery()
                .eq(UserFollow::getFollowerId, userId)
                .in(UserFollow::getFolloweeId, authorIds)
                .eq(UserFollow::getIsDeleted, false)
                .list().stream().collect(Collectors.toMap(UserFollow::getFolloweeId, f -> true));
        return authorIds.stream().map(id -> collect.getOrDefault(id, false)).toList();
    }

    @Override
    public void removeFan(Long followerId) {
        Long userId = getUserId();
        // 取消关注
        int row = baseMapper.cancelFollow(followerId, userId);
        // 更新用户信息 -> 被关注者粉丝数减一,关注者关注数量减一
        if (row > 0) {

            adjustCountersQuietly(List.of(
                    new CounterAdjust(userId, "fans", -1),
                    new CounterAdjust(followerId, "attentionCount", -1)));
        }
        // 延迟同步 ES 用户文档
        esSyncPublisher.syncUserDoc(userId);
        return;
    }

    @Override
    public PageResult<UserRelationshipVO> getInterrelated(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<Long> page = baseMapper.selectInterrelatedPage(pageQuery.toPage(), userId);
        if (page == null) {

            return PageResult.of(0, 0, List.of());
        }
        List<Long> records = page.getRecords() != null ? page.getRecords() : List.of();
        if (records.isEmpty()) {

            return PageResult.of(page.getTotal(), page.getPages(), List.of());
        }
        List<UserRelationshipVO> voList = getUserRelationshipVOS(records);
        return PageResult.of(page.getTotal(), page.getPages(), voList);
    }

    private List<UserRelationshipVO> getUserRelationshipVOS(List<Long> records) {

        List<UserSummary> users = userPort.listByIds(records);
        // 转换为 Map
        Map<Long, UserSummary> userMap = users.stream()
                .collect(Collectors.toMap(UserSummary::getId, u -> u));
        // 按原始顺序重组列表
        List<UserSummary> orderedList = records.stream().map(userMap::get)
                .filter(Objects::nonNull)
                .toList();
        // 转vo
        return orderedList.stream().map(u -> {
            UserRelationshipVO vo = new UserRelationshipVO();
            vo.setNickname(u.getNickname());
            vo.setAvatarUrl(storageUtil.signGetUrl(u.getAvatarUrl(), STORAGE_READ_SIGN_EXPIRE_SECONDS));
            vo.setIntroduction(u.getIntroduction());
            vo.setUserId(u.getId());
            return vo;
        }).toList();
    }
}
