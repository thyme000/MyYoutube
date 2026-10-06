package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.RegexUtils;
import org.springframework.stereotype.Component;

@Component
public class EmailFormatStep extends RegisterStep {

    public EmailFormatStep() {
        super(30);
    }

    @Override
    public void apply(RegisterRequestDTO dto) {
        if (RegexUtils.isEmailInvalid(dto.getEmail())) {
            throw new BizException(BizException.BIZ_ERROR, "邮箱格式错误!");
        }
    }
}
