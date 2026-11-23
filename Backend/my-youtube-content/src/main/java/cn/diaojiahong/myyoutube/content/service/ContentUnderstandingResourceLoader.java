package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource;
import cn.diaojiahong.myyoutube.content.domain.ContentUnderstandingResource.Snapshot;
import cn.diaojiahong.myyoutube.content.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.content.mapper.ContentUnderstandingMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 仅供写事务外的展示读取；独立只读事务限制查询时间，不能用于读取自身未提交的修改。 */
@Service
@RequiredArgsConstructor
public class ContentUnderstandingResourceLoader {
    private final ContentUnderstandingMapper rows;

    @Transactional(readOnly = true, timeout = 5, propagation = Propagation.REQUIRES_NEW)
    public ContentUnderstandingResource load(Long id) {
        ContentUnderstanding row = rows.selectById(id);
        if (row == null) {
            return null;
        }

        return Snapshot.from(row).toResource();
    }
}
