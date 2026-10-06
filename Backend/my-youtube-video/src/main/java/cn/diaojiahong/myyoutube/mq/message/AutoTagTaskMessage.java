package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 机器打标任务消息(video 服务自产自销), contentType 与 ContentTypeEnum.code 一致
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class AutoTagTaskMessage {

    private Long contentId;

    private String contentType;
}
