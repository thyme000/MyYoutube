package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.mapper.VideoMapper;
import cn.diaojiahong.myyoutube.video.service.IVideoService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * 视频信息表 服务实现类
 */
@Service
public class VideoServiceImpl extends ServiceImpl<VideoMapper, Video> implements IVideoService {
}
