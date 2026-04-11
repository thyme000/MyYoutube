package cn.diaojiahong.myyoutube.chat.mongo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class MessageListPageQuery {
    // 序列
    private Long seq;
    // 查询条数
    @NotNull(message = "查询条数不能为空")
    @Min(value = 1, message = "查询条数不能小于1")
    @Max(value = 100, message = "查询条数不能大于100")
    private Integer size;
}
