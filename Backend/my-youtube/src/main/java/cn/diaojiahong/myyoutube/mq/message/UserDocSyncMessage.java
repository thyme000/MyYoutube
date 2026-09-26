package cn.diaojiahong.myyoutube.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class UserDocSyncMessage {
    // 需要同步 ES 文档的用户ID, 消费端以 MySQL 数据为准写入
    private Long userId;
}
