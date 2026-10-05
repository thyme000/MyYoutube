package cn.diaojiahong.myyoutube.common.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 用户 ES 文档同步消息, 消费端以 MySQL 为准重建。
 * 生产方在 user 服务, 消费方在 search 服务, 消息体放 common 单点。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDocSyncMessage implements Serializable {
    private Long userId;
}
