package cn.diaojiahong.myyoutube.user.service;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.BusinessTime;
import cn.diaojiahong.myyoutube.user.api.UserAdministrationPort;
import cn.diaojiahong.myyoutube.user.api.enums.UserRole;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.domain.po.UserRoleChange;
import cn.diaojiahong.myyoutube.user.mapper.UserMapper;
import cn.diaojiahong.myyoutube.user.mapper.UserRoleChangeMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class UserAdministrationService implements UserAdministrationPort {
    private final UserMapper users;
    private final UserRoleChangeMapper changes;

    @Override
    public PageResult<Administrator> page(Query query) {
        requireSuperAdmin(users.selectOne(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getRole)
                .eq(User::getId, query.actorId()).eq(User::getIsDelete, 0)));
        PageQuery.validate(query.pageNo(), query.pageSize());

        Page<User> page = users.selectPage(new Page<>(query.pageNo(), query.pageSize()),
                Wrappers.<User>lambdaQuery().select(User::getId, User::getUsername, User::getNickname, User::getRole)
                        .eq(User::getIsDelete, 0).in(User::getRole, UserRole.ADMIN, UserRole.SUPER_ADMIN)
                        .orderByDesc(User::getId));
        List<Administrator> administrators = page.getRecords().stream()
                .map(user -> new Administrator(user.getId(), user.getUsername(), user.getNickname(), user.getRole()))
                .toList();
        return PageResult.of(page.getTotal(), page.getPages(), administrators);
    }

    @Override
    @Transactional
    public Change grant(Long userId, Long actorId) {
        return change(userId, actorId, UserRole.USER, UserRole.ADMIN);
    }

    @Override
    @Transactional
    public Change revoke(Long userId, Long actorId) {
        return change(userId, actorId, UserRole.ADMIN, UserRole.USER);
    }

    private Change change(Long userId, Long actorId, UserRole previousRole, UserRole newRole) {
        if (userId == null || userId <= 0 || actorId == null || actorId <= 0) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "用户ID须为正数");
        }

        // 按主键顺序锁定操作者和目标，权限复核与角色变更使用同一事务。
        List<User> locked = users.selectList(Wrappers.<User>lambdaQuery()
                .select(User::getId, User::getRole).in(User::getId, actorId, userId)
                .eq(User::getIsDelete, 0).orderByAsc(User::getId).last("FOR UPDATE"));
        Map<Long, User> byId = locked.stream().collect(Collectors.toMap(User::getId, Function.identity()));
        requireSuperAdmin(byId.get(actorId));

        User target = byId.get(userId);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "目标用户不存在或已删除");
        }

        if (target.getRole() == UserRole.SUPER_ADMIN) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "不能通过此接口修改超级管理员");
        }

        if (target.getRole() == newRole) {
            return new Change(userId, newRole, false);
        }

        if (target.getRole() != previousRole) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "目标用户角色已变化");
        }

        int updated = users.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId).eq(User::getIsDelete, 0).eq(User::getRole, previousRole)
                .set(User::getRole, newRole));
        if (updated != 1) {
            throw new BizException(ErrorCode.STATE_CONFLICT, "目标用户状态已变化，请重试");
        }

        changes.insert(new UserRoleChange().setActorId(actorId).setTargetUserId(userId)
                .setPreviousRole(previousRole).setNewRole(newRole).setCreateTime(BusinessTime.now()));
        return new Change(userId, newRole, true);
    }

    private void requireSuperAdmin(User actor) {
        if (actor == null || actor.getRole() != UserRole.SUPER_ADMIN) {
            throw new BizException(ErrorCode.SUPER_ADMIN_REQUIRED);
        }
    }
}
