package cn.diaojiahong.myyoutube.video.service;

import cn.diaojiahong.myyoutube.content.service.impl.ContentFeedServiceImpl;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioContentQuery;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioContentCursor;
import cn.diaojiahong.myyoutube.video.domain.enums.ContentSortEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Set;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AudioContentService {
    private final IAudioService audios;
    private final ContentMapper contents;
    private final ContentFeedServiceImpl cards;

    public CursorPage<ContentFeedVO, String> list(Long audioId, Long viewer, AudioContentQuery query) {
        ContentSortEnum order = query.order();
        AudioContentCursor cursor = AudioContentCursor.decode(query.getCursor(), audioId, order);
        audios.getPublic(audioId);
        List<Content> candidates = contents.publicAudioContents(audioId, order, cursor, query.getPageSize() + 1);
        boolean hasMore = candidates.size() > query.getPageSize();
        List<Content> rows = hasMore ? candidates.subList(0, query.getPageSize()) : candidates;
        String nextCursor = null;
        if (hasMore) {
            Content last = rows.get(rows.size() - 1);
            nextCursor = new AudioContentCursor(audioId, order, last.getLikeCount(), last.getCreateTime(), last.getId()).encode();
        }
        // 查询已同时核验音频和内容的公开资格，复用卡片装配时无需重复查音频。
        return new CursorPage<>(cards.assembleCards(viewer, rows, Set.of()), nextCursor);
    }
}
