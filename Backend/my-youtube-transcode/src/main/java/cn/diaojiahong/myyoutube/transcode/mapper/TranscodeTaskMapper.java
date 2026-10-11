package cn.diaojiahong.myyoutube.transcode.mapper;

import cn.diaojiahong.myyoutube.transcode.domain.po.TranscodeTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.*;
import java.util.List;

public interface TranscodeTaskMapper extends BaseMapper<TranscodeTask> {
    @Select("SELECT * FROM transcode_task WHERE status IN ('PENDING','PROCESSING') AND next_dispatch_at <= CURRENT_TIMESTAMP ORDER BY next_dispatch_at,id LIMIT 100")
    List<TranscodeTask> due();

    @Update("UPDATE transcode_task SET next_dispatch_at=TIMESTAMPADD(SECOND,60,CURRENT_TIMESTAMP) WHERE id=#{id} AND status IN ('PENDING','PROCESSING') AND next_dispatch_at <= CURRENT_TIMESTAMP")
    int reserveDispatch(@Param("id") Long id);
}
