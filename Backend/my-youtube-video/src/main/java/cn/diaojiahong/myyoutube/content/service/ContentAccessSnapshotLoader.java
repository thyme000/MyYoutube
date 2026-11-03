package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.ContentAccessSnapshot;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 仅供写事务外的展示读入口调用。
 * REQUIRES_NEW 会挂起外层事务，读取已提交数据，不能用于读取调用方尚未提交的修改。
 * REPEATABLE_READ 让内容与音频两条查询共享同一数据库快照，查询超时由事务限制。
 */
@Service
@RequiredArgsConstructor
public class ContentAccessSnapshotLoader {
    private final ContentMapper contents;
    private final AudioContentAccess audioAccess;

    @Transactional(readOnly = true, timeout = 5, propagation = Propagation.REQUIRES_NEW,
            isolation = Isolation.REPEATABLE_READ)
    public ContentAccessSnapshot load(Long id) {
        Content content = contents.selectById(id);
        if (content == null) {
            return null;
        }

        // 复用历史引用规则：音频不存在或被封禁才拦截，私密和软删除不影响已有作品。
        boolean audioBlocked = audioAccess.isReferenceBlocked(content);
        return new ContentAccessSnapshot(content, audioBlocked);
    }
}
