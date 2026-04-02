package cn.diaojiahong.myyoutube.follow.service.impl;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.utils.AliOssUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.follow.domain.po.UserFollow;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserFanVO;
import cn.diaojiahong.myyoutube.follow.domain.vo.UserFollowVO;
import cn.diaojiahong.myyoutube.follow.mapper.UserFollowMapper;
import cn.diaojiahong.myyoutube.follow.service.IUserFollowService;
import cn.diaojiahong.myyoutube.user.domain.dto.UserDTO;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
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
    private final AliOssUtil aliOssUtil;
    // 过期时间两个小时
    private static final int OSS_EXPIRE = 2;

    @Override
    @Transactional
    public Result<Void> follow(Long authorId) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        Long userId = user.getId();
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

        return Result.success();
    }

    @Transactional
    @Override
    public Result<Void> cancelFollow(Long authorId) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        Long userId = user.getId();
        // 取消关注
        int row = baseMapper.cancelFollow(userId, authorId);
        // 更新用户信息 -> 被关注者粉丝数减一,关注者关注数量减一
        if (row > 0) {
            userService.lambdaUpdate().eq(User::getId, authorId).setDecrBy(User::getFans, 1).gt(User::getFans, 0).update();
            userService.lambdaUpdate().eq(User::getId, userId).setDecrBy(User::getAttentionCount, 1).gt(User::getAttentionCount, 0).update();
        }

        return Result.success();
    }

    @Override
    public Result<PageResult<UserFollowVO>> getUserFollowList(PageQuery pageQuery) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        Long userId = user.getId();
        Page<UserFollow> page = lambdaQuery().eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getCreateTime)
                .page(pageQuery.toPage());
        List<UserFollow> follows = page.getRecords();

        List<Long> list = follows.stream().map(UserFollow::getFolloweeId).toList();

        List<User> users = userService.listByIds(list);
        // 转换为 Map
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // 按原始顺序重组列表
        List<User> orderedList = list.stream().map(userMap::get)
                .filter(Objects::nonNull)
                .toList();

        // 转vo
        List<UserFollowVO> voList = orderedList.stream().map(u -> {
            UserFollowVO vo = new UserFollowVO();
            vo.setNickname(u.getNickname());
            vo.setAvatarUrl(aliOssUtil.signGetUrl(u.getAvatarUrl(), OSS_EXPIRE));
            vo.setIntroduction(u.getIntroduction());
            vo.setFolloweeId(u.getId());
            return vo;
        }).toList();

        return Result.success(PageResult.of(page.getTotal(), page.getPages(), voList));
    }

    @Override
    public Result<Boolean> isFollow(Long authorId) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        Long userId = user.getId();
        UserFollow one = lambdaQuery()
                .eq(UserFollow::getFollowerId, userId)
                .eq(UserFollow::getFolloweeId, authorId).one();
        return Result.success(one != null && !one.getIsDeleted());
    }

    @Override
    public Result<PageResult<UserFanVO>> getFansList(PageQuery pageQuery) {
        UserDTO user = UserContext.getUser();
        if (user == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        Long userId = user.getId();
        Page<UserFollow> page = lambdaQuery().eq(UserFollow::getFolloweeId, userId)
                .eq(UserFollow::getIsDeleted, false)
                .orderByDesc(UserFollow::getCreateTime)
                .page(pageQuery.toPage());
        List<UserFollow> follows = page.getRecords();
        List<Long> list = follows.stream().map(UserFollow::getFollowerId).toList();
        List<User> users = userService.listByIds(list);
        // 转换为 Map
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));
        // 按原始顺序重组列表
        List<User> orderedList = list.stream().map(userMap::get)
                .filter(Objects::nonNull)
                .toList();
        // 转vo
        List<UserFanVO> voList = orderedList.stream().map(u -> {
            UserFanVO vo = new UserFanVO();
            vo.setNickname(u.getNickname());
            vo.setAvatarUrl(aliOssUtil.signGetUrl(u.getAvatarUrl(), OSS_EXPIRE));
            vo.setIntroduction(u.getIntroduction());
            vo.setFanId(u.getId());
            return vo;
        }).toList();
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
    public Map<Long, Boolean> isFollowOnList(List<Long> authorIds, Long userId) {
        if (userId == null) {
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        if (authorIds == null || authorIds.isEmpty()) {
            return Map.of();
        }
        return lambdaQuery()
                .eq(UserFollow::getFollowerId, userId)
                .in(UserFollow::getFolloweeId, authorIds)
                .list().stream().distinct().collect(Collectors.toMap(UserFollow::getFolloweeId, u -> !u.getIsDeleted()));
    }

}