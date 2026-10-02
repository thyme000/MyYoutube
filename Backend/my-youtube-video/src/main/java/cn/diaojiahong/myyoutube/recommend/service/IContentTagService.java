package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;

/**
 * <p>
 * 内容-标签关联表 服务类
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface IContentTagService extends IService<ContentTag> {

    /**
     * 保存某个内容的标签关联关系（先清理旧关联，再写入新关联）。
     *
     * @param tags      标签字符串（逗号分隔）
     * @param contentId 内容 ID
     * @return 实际生效的标签 id 列表
     */
    List<Long> saveTags(String tags, Long contentId);

}
