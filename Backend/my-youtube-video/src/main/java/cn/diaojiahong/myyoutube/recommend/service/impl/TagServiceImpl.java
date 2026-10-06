package cn.diaojiahong.myyoutube.recommend.service.impl;

import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import cn.diaojiahong.myyoutube.recommend.mapper.TagMapper;
import cn.diaojiahong.myyoutube.recommend.service.ITagService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.stereotype.Service;

/**
 * <p>
 * 标签表 服务实现类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
@Service
public class TagServiceImpl extends ServiceImpl<TagMapper, Tag> implements ITagService {
}
