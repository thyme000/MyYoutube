package cn.diaojiahong.myyoutube.video.mapper;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;

import java.util.List;
import java.util.Set;

/**
 * <p>
 * 视频信息表 Mapper 接口
 * </p>
 *
 * @author thyme
 * @since 2026-03-22
 */
public interface VideoMapper extends BaseMapper<Video> {

    List<Video> selectOrderedList(Set<String> ids);
}
