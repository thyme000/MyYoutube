package cn.diaojiahong.myyoutube.recommend.service;

import cn.diaojiahong.myyoutube.recommend.domain.po.ContentTag;
import com.baomidou.mybatisplus.extension.service.IService;

import java.util.List;
import java.util.Map;

/**
 * <p>
 * 内容-标签关联表 服务类（机器打标专属写入方）
 * </p>
 *
 * @author thyme
 * @since 2026-03-25
 */
public interface IContentTagService extends IService<ContentTag> {

    /**
     * 覆盖式写入某内容的机器标签：先清旧关联再按置信度写入。
     *
     * @param contentId  内容 ID
     * @param tagWeights 标签 id → 置信度(0~1)
     * @return 实际生效的标签 id 列表
     */
    List<Long> saveAutoTags(Long contentId, Map<Long, Double> tagWeights);

}
