package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.page.CursorPage;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursor;
import cn.diaojiahong.myyoutube.content.domain.dto.AuthorContentCursorQuery;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentStatisticsMapper;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import cn.diaojiahong.myyoutube.video.config.AuthorContentPageProperties;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** 作者作品按时间续查；每次请求在同一快照中读取数量、作品及音频状态。 */
@Service
@Slf4j
public class AuthorContentPageService {
    private final IContentService contents;
    private final AudioContentAccess audioAccess;
    private final ContentStatisticsMapper statistics;
    private final AuthorContentPageProperties properties;
    private final LongSupplier ticker;

    @Autowired
    public AuthorContentPageService(IContentService contents, AudioContentAccess audioAccess,
                                    ContentStatisticsMapper statistics, AuthorContentPageProperties properties) {
        this(contents, audioAccess, statistics, properties, System::nanoTime);
    }

    AuthorContentPageService(IContentService contents, AudioContentAccess audioAccess,
                             ContentStatisticsMapper statistics,
                             AuthorContentPageProperties properties,
                             LongSupplier ticker) {
        this.contents = contents;
        this.audioAccess = audioAccess;
        this.statistics = statistics;
        this.properties = properties;
        this.ticker = ticker;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AuthorContentCandidates list(Long viewer, long author, AuthorContentCursorQuery query) {
        ContentTypeEnum type = query.mediaType();
        AuthorContentCursor cursor = AuthorContentCursor.decode(query.getCursor(), author, viewer, type);
        LambdaQueryWrapper<Content> filter = new LambdaQueryWrapper<Content>()
                .eq(Content::getAuthorId, author).eq(Content::getIsDelete, 0)
                .eq(Content::getStatus, ContentStatusEnum.PUBLISHED).eq(type != null, Content::getType, type)
                .eq(!Objects.equals(author, viewer), Content::getVisibility, ContentVisibilityEnum.PUBLIC.getCode());
        long displayCount = statistics.displayCount(author, type,
                Objects.equals(author, viewer) ? null : ContentVisibilityEnum.PUBLIC);
        long started = ticker.getAsLong();
        Content after = cursor == null ? null : new Content()
                .setId(cursor.contentId())
                .setCreateTime(cursor.createTime());
        Slice slice = readPage(filter, after, query.getPageSize(), started);

        String next = slice.hasMore() ? new AuthorContentCursor(author, viewer, type,
                slice.last().getCreateTime(), slice.last().getId()).encode() : null;
        return new AuthorContentCandidates(new CursorPage<>(slice.visible(), next), displayCount);
    }

    private Slice readPage(LambdaQueryWrapper<Content> filter, Content after, int pageSize, long started) {
        List<Content> visible = new ArrayList<>();
        int checked = 0;
        boolean more = true;
        do {
            try {
                int size = Math.min(pageSize - visible.size(), properties.getScanLimit() - checked);
                List<Content> batch = load(filter, after, size);
                List<Content> rows = batch.subList(0, Math.min(size, batch.size()));
                Set<Long> blocked = audioAccess.blocked(rows);
                visible.addAll(rows.stream().filter(row -> !blocked.contains(row.getId())).toList());

                checked += rows.size();
                more = batch.size() > size;
                if (!rows.isEmpty()) after = rows.get(rows.size() - 1);
            } catch (DataAccessException failure) {
                if (checked == 0) throw failure;
                log.warn("Author content refill stopped errorType={}", failure.getClass().getSimpleName());
                break;
            }
        } while (more && visible.size() < pageSize && canScan(checked, started));
        return new Slice(visible, after, more);
    }

    private List<Content> load(LambdaQueryWrapper<Content> filter, Content after, int size) {
        LambdaQueryWrapper<Content> scan = filter.clone();
        if (after != null) scan.and(w -> w.lt(Content::getCreateTime, after.getCreateTime())
                .or(tie -> tie.eq(Content::getCreateTime, after.getCreateTime()).lt(Content::getId, after.getId())));
        return contents.list(scan.orderByDesc(Content::getCreateTime, Content::getId).last("LIMIT " + (size + 1)));
    }

    private boolean canScan(int checked, long started) {
        return checked < properties.getScanLimit()
                && ticker.getAsLong() - started < TimeUnit.MILLISECONDS.toNanos(properties.getScanBudgetMs());
    }

    private record Slice(List<Content> visible, Content last, boolean hasMore) {
    }

    public record AuthorContentCandidates(CursorPage<Content, String> page, long displayCount) {
    }
}
