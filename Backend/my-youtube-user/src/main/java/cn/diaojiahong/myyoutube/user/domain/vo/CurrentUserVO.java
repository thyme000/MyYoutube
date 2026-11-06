package cn.diaojiahong.myyoutube.user.domain.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class CurrentUserVO extends UserVO {
    @Schema(description = "按北京时间计算的账号注册年份")
    private int registeredYear;
}
