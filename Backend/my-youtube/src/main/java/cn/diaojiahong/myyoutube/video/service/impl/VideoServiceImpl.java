package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 视频核心表（包含长视频/短视频，分类：音乐/电影/新闻/时尚） 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-18
 */
@Service
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video> implements IVideoService {

}
