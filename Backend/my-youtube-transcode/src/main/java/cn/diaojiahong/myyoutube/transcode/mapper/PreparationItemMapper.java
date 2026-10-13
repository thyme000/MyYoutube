package cn.diaojiahong.myyoutube.transcode.mapper;

import cn.diaojiahong.myyoutube.transcode.domain.po.PreparationItem;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface PreparationItemMapper extends BaseMapper<PreparationItem> {
    @Select("""
        SELECT i.* FROM media_preparation_item i JOIN media_preparation p ON p.id=i.preparation_id
        WHERE p.draft_id=#{draftId} AND p.user_id=#{userId} AND p.parameter_version=#{parameters}
          AND p.id != #{currentId} AND i.role='IMAGE' AND i.status='READY'
          AND i.object_owner_id=i.preparation_id AND i.source_json=#{source}
        ORDER BY p.media_version DESC LIMIT 20
        """)
    List<PreparationItem> reusableImages(Long draftId, Long userId, String parameters, Long currentId, String source);

    @Select("""
        SELECT COUNT(*) FROM media_preparation_item i JOIN media_preparation p ON p.id=i.preparation_id
        WHERE i.object_key=#{key} AND i.preparation_id != #{ownerId} AND p.lifecycle='ACTIVE'
        """)
    long activeReferences(String key, Long ownerId);
}
