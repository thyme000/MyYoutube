package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import java.time.LocalDateTime;

public final class AudioIndexChanges {
    private AudioIndexChanges() {
    }

    public static LambdaUpdateWrapper<Audio> bump(LambdaUpdateWrapper<Audio> update) {
        return update.setSql("index_pending_since = COALESCE(index_pending_since, CURRENT_TIMESTAMP), index_version = index_version + 1")
                .set(Audio::getIndexAfter, LocalDateTime.now());
    }
}
