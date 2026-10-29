package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentStatisticsMapper;
import cn.diaojiahong.myyoutube.video.api.enums.ContentStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.ContentVisibilityEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** 只维护作品自身的发布贡献；音频封禁的展示扣减由读取侧完成。 */
@Service
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class ContentStatisticsService {
    private final ContentStatisticsMapper statistics;

    public void transition(Content before, ContentStatusEnum target) {
        change(before, contribution(before), target == ContentStatusEnum.PUBLISHED
                && Integer.valueOf(0).equals(before.getIsDelete()) ? visibility(before) : null);
    }

    public void delete(Content before) {
        change(before, contribution(before), null);
    }

    public void changeVisibility(Content before, ContentVisibilityEnum target) {
        ContentVisibilityEnum source = contribution(before);
        change(before, source, source == null ? null : target);
    }

    private void change(Content content, ContentVisibilityEnum before, ContentVisibilityEnum after) {
        if (Objects.equals(before, after)) return;
        List<Adjustment> adjustments = new ArrayList<>(2);
        if (before != null) adjustments.add(new Adjustment(before, -1));
        if (after != null) adjustments.add(new Adjustment(after, 1));
        // 公开/私密相向切换也按相同顺序更新，先作者分组，再音频引用分组。
        adjustments.sort(Comparator.comparing(adjustment -> adjustment.visibility().getCode()));
        for (Adjustment adjustment : adjustments) {
            requireChanged(statistics.changePublished(content.getAuthorId(), content.getType(),
                    adjustment.visibility(), adjustment.delta()), content.getId());
        }
        if (content.getAudioId() == null) return;
        // 不删除零引用分组，后续历史使用作者数仍需要这些记录。
        for (Adjustment adjustment : adjustments) {
            requireChanged(statistics.changeReferences(content.getAudioId(), content.getAuthorId(), content.getType(),
                    adjustment.visibility(), adjustment.delta()), content.getId());
        }
    }

    private ContentVisibilityEnum contribution(Content content) {
        return content.getStatus() == ContentStatusEnum.PUBLISHED && Integer.valueOf(0).equals(content.getIsDelete())
                ? visibility(content) : null;
    }

    private ContentVisibilityEnum visibility(Content content) {
        return Objects.requireNonNull(ContentVisibilityEnum.fromCode(content.getVisibility()), "Invalid content visibility");
    }

    private void requireChanged(int affected, Long contentId) {
        if (affected == 0) throw new IllegalStateException("Content statistics out of sync: contentId=" + contentId);
    }

    private record Adjustment(ContentVisibilityEnum visibility, int delta) {}
}
