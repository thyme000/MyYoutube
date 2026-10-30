package cn.diaojiahong.myyoutube.transcode.preparation;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.transcode.domain.po.MediaPreparation;
import cn.diaojiahong.myyoutube.transcode.mapper.MediaPreparationMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PreparationLifecycle {
    private final MediaPreparationMapper preparations;

    public MediaPreparation lock(Long id) {
        return preparations.selectOne(new LambdaQueryWrapper<MediaPreparation>().eq(MediaPreparation::getId, id).last("FOR UPDATE"));
    }

    public void requireActive(Long id) {
        MediaPreparation row = lock(id);
        if (row == null
                || !"ACTIVE".equals(row.getLifecycle())) throw new IllegalStateException("Preparation is retired or missing");
    }

    public void requireActive(ContentMedia media) {
        if (media.preparation() != null) requireActive(media.preparation().preparationId());
    }
}
