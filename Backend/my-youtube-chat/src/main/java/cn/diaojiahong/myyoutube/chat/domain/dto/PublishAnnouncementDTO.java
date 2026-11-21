package cn.diaojiahong.myyoutube.chat.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PublishAnnouncementDTO {
    @NotBlank(message = "公告内容不能为空")
    @Size(max = 2000, message = "公告内容过长")
    private String text;

    // 客户端为每次提交生成的请求id(UUID): 双击/超时重发时复用同一个值, 换内容重发时用新值
    @NotBlank(message = "clientReqId不能为空")
    private String clientReqId;
}
