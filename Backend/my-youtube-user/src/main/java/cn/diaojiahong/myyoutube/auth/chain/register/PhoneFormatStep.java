package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.RegexUtils;
import org.springframework.stereotype.Component;

@Component
public class PhoneFormatStep extends RegisterStep {

    public PhoneFormatStep() {
        super(40);
    }

    @Override
    public void apply(RegisterRequestDTO dto) {
        if (RegexUtils.isPhoneInvalid(dto.getPhone())) {
            throw new BizException(BizException.BIZ_ERROR, "手机号格式错误!");
        }
    }
}
