package cn.diaojiahong.myyoutube.follow.service.impl;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
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
import cn.diaojiahong.myyoutube.user.api.FollowSnapshotKey;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.port.UserPortAdapter;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

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

    private final IUserService userService;
    private final UserPortAdapter userPortAdapter;
    private final S3StorageUtil storageUtil;
    private final EsSyncPublisher esSyncPublisher;
    private final TransactionTemplate transactionTemplate;
    private final StringRedisTemplate redis;

    private static final long STORAGE_READ_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    @Override
    @ObservedBusiness(BusinessOperation.FOLLOW)
    public void follow(Long authorId) {
        Long userId = getUserId();
        if (userId.equals(authorId)) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "不能关注自己");
        }

        // 重复关注时 ON DUPLICATE 无实际变更影响行数=0, 计数不会重复累加
        long id = IdWorker.getId();
        transactionTemplate.executeWithoutResult(tx -> {
            int row = baseMapper.followOrRestore(id, userId, authorId);
            if (row > 0) {
                AbstractCounterTarget.guardedDelta(userService, User::getId, authorId, User::getFans, 1);
                AbstractCounterTarget.guardedDelta(userService, User::getId, userId, User::getAttentionCount, 1);
            }
        });
        evictFollowSnapshotQuietly(userId);
        // ES 文档同步走 MQ 延迟合并, 留在事务外
        esSyncPublisher.syncUserDoc(authorId);
    }

    /** 尽力而为失效读侧关注快照: 删除失败只记日志, 滞后由缓存 TTL 兜底 */
    private void evictFollowSnapshotQuietly(Long userId) {
        try {
            redis.delete(FollowSnapshotKey.key(userId));
        } catch (Exception e) {
            log.warn("关注快照缓存删除失败 userId={}", userId, e);
        }
    }

    private Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }

        return Long.valueOf(userId);
    }

    @Override
    @ObservedBusiness(BusinessOperation.UNFOLLOW)
    public void cancelFollow(Long authorId) {
        Long userId = getUserId();
        transactionTemplate.executeWithoutResult(tx -> {
            int row = baseMapper.cancelFollow(userId, authorId);
            // 未关注再取关影响行数=0, 计数不动
            if (row > 0) {
                AbstractCounterTarget.guardedDelta(userService, User::getId, authorId, User::getFans, -1);
                AbstractCounterTarget.guardedDelta(userService, User::getId, userId, User::getAttentionCount, -1);
            }
        });
        evictFollowSnapshotQuietly(userId);
        // ES 用户文档延迟合并
        esSyncPublisher.syncUserDoc(authorId);
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
        return isFollow(getUserId(), authorId);
    }

    @Override
    public Boolean isFollow(Long userId, Long authorId) {
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
                .select(UserFollow::getFolloweeId)
                .list().stream().map(UserFollow::getFolloweeId).toList();
    }

    @Override
    public List<Boolean> isFollowOnList(List<Long> authorIds, Long userId) {
        if (userId == null) {

            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
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
        transactionTemplate.executeWithoutResult(tx -> {
            // 移除粉丝 = 解除 followerId -> userId 的关注关系
            int row = baseMapper.cancelFollow(followerId, userId);
            if (row > 0) {
                AbstractCounterTarget.guardedDelta(userService, User::getId, userId, User::getFans, -1);
                AbstractCounterTarget.guardedDelta(userService, User::getId, followerId, User::getAttentionCount, -1);
            }
        });
        evictFollowSnapshotQuietly(followerId);
        // ES 用户文档延迟合并
        esSyncPublisher.syncUserDoc(userId);
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
        List<UserSummary> users = userPortAdapter.listByIds(records);
        Map<Long, UserSummary> userMap = users.stream()
                .collect(Collectors.toMap(UserSummary::getId, u -> u));
        // 按原始顺序重组列表
        List<UserSummary> orderedList = records.stream().map(userMap::get)
                .filter(Objects::nonNull)
                .toList();
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
