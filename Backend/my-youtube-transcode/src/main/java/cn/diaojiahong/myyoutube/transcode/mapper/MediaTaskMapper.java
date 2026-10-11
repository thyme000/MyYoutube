package cn.diaojiahong.myyoutube.transcode.mapper;

import cn.diaojiahong.myyoutube.transcode.domain.po.MediaTask;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

public interface MediaTaskMapper extends BaseMapper<MediaTask> {
    @org.apache.ibatis.annotations.Select("SELECT phase,task_type,status,COUNT(*) AS total FROM media_task "
            + "WHERE status IN ('PENDING','READY','RUNNING','RETRY_WAIT','DEAD','BLOCKED') GROUP BY phase,task_type,status")
    java.util.List<java.util.Map<String,Object>> backlog();
}
