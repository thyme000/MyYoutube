package cn.diaojiahong.myyoutube.video.mapper;
import cn.diaojiahong.myyoutube.video.domain.po.ContentPublicationEvent;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface ContentPublicationEventMapper extends BaseMapper<ContentPublicationEvent> {
    List<Long> pendingAudioCountIds(@Param("audioId") long audioId, @Param("limit") int limit);

    int finishAudioCounts(@Param("ids") List<Long> ids);

    int completeIfReady(@Param("ids") List<Long> ids);
}
