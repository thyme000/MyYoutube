package cn.diaojiahong.myyoutube.follow.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserRelationshipVO;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.mq.message.UserDocSyncMessage;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
@Service
@RequiredArgsConstructor
public class UserFollowServiceImpl extends ServiceImpl<UserFollowMapper, UserFollow> implements IUserFollowService {

    private final IUserService userService;
    private final S3StorageUtil storageUtil;
    private final RocketMQTemplate rocketMQTemplate;

    private static final long STORAGE_READ_SIGN_EXPIRE_SECONDS = 24 * 60 * 60;

    @Override
    @Transactional
    public Result<Void> follow(Long authorId) {
        Long userId = getUserId();
        if (userId.equals(authorId)) {
            throw new BizException(BizException.USER_ERROR, "不能关注自己");
        }
        // 存在就修改,不存在就新增
        long id = IdWorker.getId();
        int row = baseMapper.followOrRestore(id, userId, authorId);
        // 关注成功后，更新用户信息 -> 被关注者粉丝数加一,关注者关注数量加一
        if (row > 0) {
            userService.lambdaUpdate().eq(User::getId, authorId).setIncrBy(User::getFans, 1).update();
            userService.lambdaUpdate().eq(User::getId, userId).setIncrBy(User::getAttentionCount, 1).update();
        }
        // 延迟同步 ES 用户文档, 短时间多次变更天然合并(消费端以 MySQL 为准)
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_USER,
                new UserDocSyncMessage(authorId), 5);
        return Result.success();
    }

    private Long getUserId() {
        String userId = UserContext.getUserId();
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }

    @Transactional
    @Override
    public Result<Void> cancelFollow(Long authorId) {
        Long userId = getUserId();
        // 取消关注
        int row = baseMapper.cancelFollow(userId, authorId);
        // 更新用户信息 -> 被关注者粉丝数减一,关注者关注数量减一
        if (row > 0) {
            userService.lambdaUpdate().eq(User::getId, authorId).setDecrBy(User::getFans, 1).gt(User::getFans, 0).update();
            userService.lambdaUpdate().eq(User::getId, userId).setDecrBy(User::getAttentionCount, 1).gt(User::getAttentionCount, 0).update();
        }
        // 延迟同步 ES 用户文档
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_USER,
                new UserDocSyncMessage(authorId), 5);
        return Result.success();
    }

    @Override
    public Result<PageResult<UserRelationshipVO>> getUserFollowList(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<UserFollow> page = lambdaQuery().eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getCreateTime)
                .page(pageQuery.toPage());
        List<UserFollow> follows = page.getRecords();
        if (follows == null || follows.isEmpty()) {
            return Result.success(PageResult.of(page.getTotal(), page.getPages(), List.of()));
        }
        List<Long> list = follows.stream().map(UserFollow::getFolloweeId).toList();

        List<UserRelationshipVO> voList = getUserRelationshipVOS(list);

        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Override
    public Result<Boolean> isFollow(Long authorId) {
        Long userId = getUserId();
        UserFollow one = lambdaQuery()
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getFolloweeId, authorId).one();
        return Result.success(one != null && !one.getIsDeleted());
    }

    @Override
    public Boolean interrelated(Long userId, List<Long> memberIds) {
        int expectedCount = (int) memberIds.stream().distinct().count();
        return baseMapper.interrelated(userId, memberIds, expectedCount);
    }


    @Override
    public Result<PageResult<UserRelationshipVO>> getFansList(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<UserFollow> page = lambdaQuery().eq(UserFollow::getFolloweeId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getCreateTime)
                .page(pageQuery.toPage());
        List<UserFollow> follows = page.getRecords();
        if (follows == null || follows.isEmpty()) {
            return Result.success(PageResult.of(page.getTotal(), page.getPages(), List.of()));
        }
        List<Long> list = follows.stream().map(UserFollow::getFollowerId).toList();
        List<UserRelationshipVO> voList = getUserRelationshipVOS(list);
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
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
    public Result<Void> removeFan(Long followerId) {
        Long userId = getUserId();
        // 取消关注
        int row = baseMapper.cancelFollow(followerId, userId);
        // 更新用户信息 -> 被关注者粉丝数减一,关注者关注数量减一
        if (row > 0) {
            userService.lambdaUpdate().eq(User::getId, userId).setDecrBy(User::getFans, 1).gt(User::getFans, 0).update();
            userService.lambdaUpdate().eq(User::getId, followerId).setDecrBy(User::getAttentionCount, 1).gt(User::getAttentionCount, 0).update();
        }
        // 延迟同步 ES 用户文档
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_USER,
                new UserDocSyncMessage(userId), 5);
        return Result.success();
    }

    @Override
    public Result<PageResult<UserRelationshipVO>> getInterrelated(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<Long> page = baseMapper.selectInterrelatedPage(pageQuery.toPage(), userId);
        if (page == null) {
            return Result.success(PageResult.of(0, 0, List.of()));
        }
        List<Long> records = page.getRecords() != null ? page.getRecords() : List.of();
        if (records.isEmpty()) {
            return Result.success(PageResult.of(page.getTotal(), page.getPages(), List.of()));
        }
        List<UserRelationshipVO> voList = getUserRelationshipVOS(records);
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    private List<UserRelationshipVO> getUserRelationshipVOS(List<Long> records) {
        List<User> users = userService.listByIds(records);
        // 转换为 Map
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // 按原始顺序重组列表
        List<User> orderedList = records.stream().map(userMap::get)
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
