package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.RegexUtils;
import org.springframework.stereotype.Component;

@Component
public class PasswordFormatStep extends RegisterStep {

    public PasswordFormatStep() {
        super(20);
    }

    @Override
    public void apply(RegisterRequestDTO dto) {
        if (RegexUtils.isPasswordInvalid(dto.getPassword())) {
            throw new BizException(BizException.BIZ_ERROR, "密码格式错误，需10~32位字母/数字/下划线!");
        }
    }
}
