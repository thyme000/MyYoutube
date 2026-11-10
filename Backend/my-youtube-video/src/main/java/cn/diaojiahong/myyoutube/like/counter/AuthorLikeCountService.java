package cn.diaojiahong.myyoutube.like.counter;

import cn.diaojiahong.myyoutube.common.counter.AbstractCounterTarget;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.like.domain.po.AuthorLikeCount;
import cn.diaojiahong.myyoutube.like.mapper.AuthorLikeCountMapper;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.ReceivedLikeUpdate;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 作者获赞跟作品赞数走同一个本地事务。提交后把绝对值和 version 抄到 user 服务；
 * version 大于 synced_version 的行留在表上，下一轮再抄。确认时要求 version 仍是发出去的那一版。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorLikeCountService {

    private static final int PUSH_BATCH = 200;

    private final IContentService contentService;
    private final AuthorLikeCountMapper authorLikeCounts;
    private final UserPort userPort;

    @Transactional
    public void apply(long contentId, long delta) {
        Content content = lockContent(contentId);
        if (content == null) {
            return;
        }

        boolean applied = AbstractCounterTarget.guardedDelta(
                contentService, Content::getId, contentId, Content::getLikeCount, delta);
        if (!applied || content.getAuthorId() == null) {
            return;
        }

        authorLikeCounts.addDelta(content.getAuthorId(), delta);
    }

    @Transactional
    public void overwrite(long contentId, long truth) {
        Content content = lockContent(contentId);
        if (content == null) {
            return;
        }

        long current = content.getLikeCount() == null ? 0L : content.getLikeCount();
        long diff = truth - current;
        if (diff != 0) {
            contentService.lambdaUpdate().eq(Content::getId, contentId).set(Content::getLikeCount, truth).update();
        }
        if (diff == 0 || content.getAuthorId() == null) {
            return;
        }

        authorLikeCounts.addDelta(content.getAuthorId(), diff);
    }

    /** 抄写 version 仍大于 synced_version 的作者。整批 Feign 失败整批下轮再抄；未接受的单行下轮再抄 */
    public void pushPending() {
        List<AuthorLikeCount> pending = authorLikeCounts.selectList(new LambdaQueryWrapper<AuthorLikeCount>()
                .apply("version > synced_version")
                .orderByAsc(AuthorLikeCount::getAuthorId)
                .last("LIMIT " + PUSH_BATCH));
        if (pending.isEmpty()) {
            return;
        }

        List<ReceivedLikeUpdate> updates = new ArrayList<>(pending.size());
        for (AuthorLikeCount row : pending) {
            updates.add(new ReceivedLikeUpdate(row.getAuthorId(), row.getLikeCount(), row.getVersion()));
        }

        List<Long> acceptedIds;
        try {
            acceptedIds = userPort.overwriteReceivedLikes(updates);
        } catch (RuntimeException e) {
            log.warn("作者获赞批量抄写失败, 整批下轮重试, size={}", pending.size(), e);
            return;
        }

        Set<Long> accepted = new HashSet<>(acceptedIds == null ? List.of() : acceptedIds);
        for (AuthorLikeCount row : pending) {
            if (!accepted.contains(row.getAuthorId())) {
                continue;
            }

            authorLikeCounts.update(null, new LambdaUpdateWrapper<AuthorLikeCount>()
                    .eq(AuthorLikeCount::getAuthorId, row.getAuthorId())
                    .eq(AuthorLikeCount::getVersion, row.getVersion())
                    .lt(AuthorLikeCount::getSyncedVersion, row.getVersion())
                    .set(AuthorLikeCount::getSyncedVersion, row.getVersion()));
        }
    }

    private Content lockContent(long contentId) {
        return contentService.lambdaQuery()
                .eq(Content::getId, contentId)
                .select(Content::getId, Content::getAuthorId, Content::getLikeCount)
                .last("FOR UPDATE")
                .one();
    }
}
