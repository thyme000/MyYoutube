package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.cache.ContentResourceCache;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceReader;
import cn.diaojiahong.myyoutube.content.cache.ContentResourceRegion;
import cn.diaojiahong.myyoutube.content.domain.ContentAccessSnapshot;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 仅供详情、字幕和播放的展示读路径使用，不能用于写操作校验。
 * 内容与音频状态以一个 Redis 值共享，默认条目 TTL 为 8～10 秒，不代表提交后陈旧窗口上限。
 * 用户权限每次单独判断；写事务内可能命中旧快照或回源读取已提交旧值，不能用于读取自身未提交修改。
 * 发布、封禁、删除、设置及理解产物更新由提交后双删失效；音频封禁通过现有级联和 TTL 生效。
 * 封面、标签、分类和计数等展示字段随快照短暂陈旧，TTL 从回填时起算。
 */
@Service
@RequiredArgsConstructor
public class ContentDisplayAccess {
    private final ContentResourceCache cache;
    private final ContentAccessSnapshotLoader loader;
    private final ContentAccessService access;

    public Content readable(Long id, Long viewer) {
        return readable(id, viewer, cache);
    }

    public Content readable(Long id, Long viewer, ContentResourceReader reader) {
        ContentAccessSnapshot snapshot = reader.get(ContentResourceRegion.CONTENT_STATE, id.toString(),
                ContentAccessSnapshot.class, () -> loader.load(id));
        if (snapshot == null || snapshot.audioBlocked()) {
            throw ContentAccessService.notReadable();
        }

        return access.requireVisible(snapshot.content(), viewer);
    }

    /** 统一资源访问入口，避免调用方注入两套访问服务；审核始终读取当前数据库状态。 */
    public Content forReview(Long id) {
        return access.forReview(id);
    }
}
