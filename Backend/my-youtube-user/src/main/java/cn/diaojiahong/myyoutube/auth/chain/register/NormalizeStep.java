package cn.diaojiahong.myyoutube.auth.chain.register;

import cn.diaojiahong.myyoutube.auth.domain.dto.RegisterRequestDTO;
import org.springframework.stereotype.Component;

@Component
public class NormalizeStep extends RegisterStep {

    public NormalizeStep() {
        super(50);
    }

    /** 规范化写回 DTO, 唯一性校验与落库都用 trim 后的值 */
    @Override
    public void apply(RegisterRequestDTO dto) {
        dto.setUsername(dto.getUsername().trim());
        dto.setPhone(dto.getPhone().trim());
        dto.setEmail(dto.getEmail().trim());
    }
}
