package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import org.springframework.stereotype.Component;

@Component
public class PasswordMatchStep extends RegisterStep {

    public PasswordMatchStep() {
        super(10);
    }

    @Override
    public void apply(RegisterRequestDTO dto) {
        if (!dto.getPassword().equals(dto.getConfirmPassword())) {
            throw new BizException(BizException.BIZ_ERROR, "两次密码不一致!");
        }
    }
}
