package cn.diaojiahong.myyoutube.chat.domain.base;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TimedEntity {
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt = LocalDateTime.now();
}
