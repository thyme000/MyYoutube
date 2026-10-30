package cn.diaojiahong.myyoutube.chat.mongo.base;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class BaseMongoEntity {
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
