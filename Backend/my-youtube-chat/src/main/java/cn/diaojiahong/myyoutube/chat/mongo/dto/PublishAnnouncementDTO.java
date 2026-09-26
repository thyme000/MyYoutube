package cn.diaojiahong.myyoutube.chat.mongo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class PublishAnnouncementDTO {
    @NotBlank(message = "公告内容不能为空")
    @Size(max = 4000, message = "公告内容过长")
    private String text;
}
