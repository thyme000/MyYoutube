package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.user.domain.po.User;
import cn.diaojiahong.myyoutube.user.service.IUserService;
import org.springframework.stereotype.Component;

@Component
public class UniqueCheckStep extends RegisterStep {

    private final IUserService userService;

    public UniqueCheckStep(IUserService userService) {
        super(60);
        this.userService = userService;
    }

    /** 一次查询覆盖三列; check-then-insert 的并发窗口由 DB 唯一索引兜底 */
    @Override
    public void apply(RegisterRequestDTO dto) {
        User conflict = userService.lambdaQuery()
                .eq(User::getUsername, dto.getUsername())
                .or().eq(User::getPhone, dto.getPhone())
                .or().eq(User::getEmail, dto.getEmail())
                .last("LIMIT 1")
                .one();
        if (conflict == null) {
            return;
        }
        String reason = dto.getUsername().equals(conflict.getUsername()) ? "用户名已存在!"
                : dto.getPhone().equals(conflict.getPhone()) ? "手机号已被注册!"
                : "邮箱已被注册!";
        throw new BizException(BizException.BIZ_ERROR, reason);
    }
}
