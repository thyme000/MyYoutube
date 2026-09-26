package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.po.Tag;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 标签表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface ITagService extends IService<Tag> {

    List<Long> getTagIdsBySlugs(String tags);
}
