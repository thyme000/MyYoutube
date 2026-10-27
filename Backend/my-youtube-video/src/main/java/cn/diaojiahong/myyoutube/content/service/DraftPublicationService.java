package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.monitoring.MetricNames;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.video.config.PublicationProperties;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import cn.diaojiahong.myyoutube.common.mq.message.DraftPublicationMessage;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.video.api.enums.MediaDraftStatusEnum;
import cn.diaojiahong.myyoutube.video.api.enums.DraftSubmissionState;
import cn.diaojiahong.myyoutube.video.domain.po.MediaDraft;
import cn.diaojiahong.myyoutube.video.domain.vo.ContentDraftVO;
import cn.diaojiahong.myyoutube.video.mapper.MediaDraftMapper;
import cn.diaojiahong.myyoutube.video.service.DraftObservation;
import cn.diaojiahong.myyoutube.video.service.DraftPreparationService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

@Slf4j
@Service
@RequiredArgsConstructor
public class DraftPublicationService {
    private static final List<MediaDraftStatusEnum> ACTIVE = List.of(MediaDraftStatusEnum.UPLOADING,
            MediaDraftStatusEnum.PROCESSING, MediaDraftStatusEnum.READY);
    private final MediaDraftMapper rows;
    private final DraftObservation observation;
    private final ContentSubmissionService submissions;
    private final ContentDraftService drafts;
    private final DraftPreparationService preparation;
    private final ContentSubmissionRules rules;
    private final PublicationProperties properties;
    private final DraftPublicationNotifications notifications;
    private final MeterRegistry metrics;

    public record Request(Integer revision) {}
    public record Receipt(Long draftId, Integer acceptedRevision, Integer currentRevision, String submissionState, String draftStatus,
                          Long contentId, Long pipelineId) {}

    public Receipt request(Long id, Long userId, Integer revision) {
        var draft = owned(id, userId);
        if (revision == null || revision < 0) throw new BizException(ErrorCode.INVALID_ARGUMENT, "请提供草稿版本");
        if (draft.getSubmissionState() != DraftSubmissionState.NONE) {
            if (!Objects.equals(draft.getSubmissionRevision(), revision)) throw new BizException(ErrorCode.DRAFT_VERSION_CHANGED);
            return receipt(draft);
        }
        requireAcceptable(draft, revision);
        rules.title(draft.getTitle());
        rules.description(draft.getDescription());
        rules.allowComment(draft.getAllowComment());
        int changed = observation.update(id, version(draft).eq(MediaDraft::getSubmissionState, DraftSubmissionState.NONE)
                .in(MediaDraft::getStatus, ACTIVE).gt(MediaDraft::getExpiresAt, LocalDateTime.now())
                .set(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING).set(MediaDraft::getSubmissionRevision, revision)
                .set(MediaDraft::getSubmissionAttempts, 0).set(MediaDraft::getSubmissionError, null)
                .set(MediaDraft::getSubmissionDeadline, now().plusMinutes(properties.getDeadlineMinutes()))
                .set(MediaDraft::getSubmissionAfter, now()));
        if (changed == 0) {
            var current = owned(id, userId);
            if (current.getSubmissionState() != DraftSubmissionState.NONE && Objects.equals(current.getSubmissionRevision(), revision)) {
                return receipt(current);
            }
            requireAcceptable(current, revision);
            throw conflict();
        }
        var accepted = owned(id, userId);
        notifications.afterCommit(accepted);
        return receipt(accepted);
    }

    public PageResult<ContentDraftVO> pending(Long userId, PageQuery query) {
        var page = rows.selectPage(query.<MediaDraft>toPage(), new LambdaQueryWrapper<MediaDraft>()
                .eq(MediaDraft::getUserId, userId).in(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING, DraftSubmissionState.FAILED)
                .orderByDesc(MediaDraft::getCreateTime).orderByDesc(MediaDraft::getId));
        return PageResult.of(page.getTotal(), page.getPages(), page.getRecords().stream().map(drafts::view).toList());
    }

    public Receipt retry(Long id, Long userId, Integer revision) {
        var draft = owned(id, userId);
        requireCurrentVersion(draft, revision);
        if (!List.of(DraftSubmissionState.PENDING, DraftSubmissionState.FAILED).contains(draft.getSubmissionState())) throw conflict();
        if (draft.getStatus() == MediaDraftStatusEnum.FAILED) {
            if (!preparation.retry(id, userId)) throw conflict();
            // 配置变化的准备重试可能更新 revision，用户素材仍是受理时的那一份。
            draft = owned(id, userId);
        }
        requireAcceptable(draft, draft.getRevision());
        if (!List.of(DraftSubmissionState.PENDING, DraftSubmissionState.FAILED).contains(draft.getSubmissionState())
                || !Objects.equals(draft.getSubmissionRevision(), draft.getRevision())) throw conflict();
        if (observation.update(id, version(draft).eq(MediaDraft::getSubmissionState, draft.getSubmissionState())
                .eq(MediaDraft::getSubmissionRevision, draft.getSubmissionRevision())
                .in(MediaDraft::getStatus, ACTIVE).gt(MediaDraft::getExpiresAt, LocalDateTime.now())
                .set(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING).set(MediaDraft::getSubmissionRevision, draft.getRevision())
                .set(MediaDraft::getSubmissionAttempts, 0).set(MediaDraft::getSubmissionError, null)
                .set(MediaDraft::getSubmissionDeadline, now().plusMinutes(properties.getDeadlineMinutes()))
                .set(MediaDraft::getSubmissionAfter, now())) == 0) throw conflict();
        var accepted = owned(id, userId);
        notifications.afterCommit(accepted);
        return receipt(accepted);
    }

    public ContentDraftVO cancel(Long id, Long userId, Integer revision) {
        var draft = owned(id, userId);
        if (draft.getStatus() == MediaDraftStatusEnum.SUBMITTED) throw new BizException(ErrorCode.DRAFT_ALREADY_SUBMITTED);
        requireCurrentVersion(draft, revision);
        if (draft.getSubmissionState() == DraftSubmissionState.NONE) return drafts.view(draft);
        if (observation.update(id, version(draft).in(MediaDraft::getStatus, MediaDraftStatusEnum.UPLOADING,
                        MediaDraftStatusEnum.PROCESSING, MediaDraftStatusEnum.READY, MediaDraftStatusEnum.FAILED)
                .gt(MediaDraft::getExpiresAt, LocalDateTime.now())
                .eq(MediaDraft::getSubmissionState, draft.getSubmissionState())
                .set(MediaDraft::getSubmissionState, DraftSubmissionState.NONE).set(MediaDraft::getSubmissionRevision, null)
                .set(MediaDraft::getSubmissionAfter, null).set(MediaDraft::getSubmissionDeadline, null)
                .set(MediaDraft::getSubmissionError, null)
                .set(MediaDraft::getSubmissionAttempts, 0).setIncrBy(MediaDraft::getRevision, 1)) == 0) throw conflict();
        return drafts.get(id, userId);
    }

    @Scheduled(fixedDelayString = "${my-youtube.publication.scan-interval-ms:2000}", initialDelay = 2000)
    @ObservedBusiness(BusinessOperation.SCAN_DRAFT_PUBLICATIONS)
    public void scan() {
        var sample = Timer.start(metrics);
        try {
            var due = rows.selectList(new LambdaQueryWrapper<MediaDraft>().eq(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING)
                    .le(MediaDraft::getSubmissionAfter, now()).orderByAsc(MediaDraft::getSubmissionAfter)
                    .orderByAsc(MediaDraft::getId).last("LIMIT " + properties.getBatchSize()));
            for (var draft : due) claimAndAdvance(draft);
        } catch (RuntimeException error) {
            metrics.counter(MetricNames.PUBLICATION_SCAN_FAILED).increment();
            throw error;
        } finally { sample.stop(metrics.timer(MetricNames.PUBLICATION_SCAN_DURATION)); }
    }

    public void consume(DraftPublicationMessage message) {
        if (message == null || message.draftId() == null || message.acceptedRevision() == null) return;
        var draft = rows.selectById(message.draftId());
        if (draft == null || draft.getSubmissionState() != DraftSubmissionState.PENDING
                || !Objects.equals(draft.getSubmissionRevision(), message.acceptedRevision())) return;
        claimAndAdvance(draft);
    }

    private void claimAndAdvance(MediaDraft draft) {
        var due = now();
        if (draft.getSubmissionAfter() == null || draft.getSubmissionAfter().isAfter(due)) return;
        var lease = due.plusSeconds(properties.getLeaseSeconds());
        if (rows.update(null, claimed(draft).set(MediaDraft::getSubmissionAfter, lease)) == 0) {
            metrics.counter(MetricNames.PUBLICATION_CLAIM_MISSED).increment();
            return;
        }
        draft.setSubmissionAfter(lease);
        try { advance(draft); }
        catch (Exception error) {
            if (expired(draft)) {
                fail(draft, "草稿已过期，请重新上传", "expired");
                return;
            }
            if (deadlineExceeded(draft)) {
                fail(draft, "发布超时，请重试或取消后编辑草稿", "deadline");
                return;
            }
            int attempts = draft.getSubmissionAttempts() + 1;
            boolean rejected = businessRejection(error);
            boolean terminal = rejected || attempts > properties.getMaxAttempts();
            String reason = rejected ? error.getMessage() : terminal
                    ? "内容提交失败，请重试；持续失败可取消发布后编辑草稿"
                    : "内容提交暂时失败，系统将自动重试";
            if (reason.length() > 500) reason = reason.substring(0, 500);
            int changed = observation.update(draft.getId(), claimed(draft).set(MediaDraft::getSubmissionAttempts, attempts)
                    .set(MediaDraft::getSubmissionState, terminal ? DraftSubmissionState.FAILED : DraftSubmissionState.PENDING)
                    .set(MediaDraft::getSubmissionAfter, terminal ? null : now().plusSeconds(properties.getRetryBackoffSeconds().get(attempts - 1)))
                    .set(terminal, MediaDraft::getSubmissionDeadline, null).set(MediaDraft::getSubmissionError, reason));
            if (terminal && changed > 0) metrics.counter(MetricNames.PUBLICATION_FAILED_TOTAL, "reason", "error").increment();
            log.warn("Draft publication failed draftId={} attempt={} terminal={} errorType={}",
                    draft.getId(), attempts, terminal, error.getClass().getSimpleName());
        }
    }

    @Scheduled(fixedDelay = 600000, initialDelay = 600000)
    @ObservedBusiness(BusinessOperation.EXPIRE_DRAFT_PUBLICATIONS)
    public void expireDeadlines() {
        long cursor = 0;
        var cutoff = now();
        while (true) {
            var page = rows.selectList(new LambdaQueryWrapper<MediaDraft>().eq(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING)
                    .gt(MediaDraft::getId, cursor).and(w -> w.isNull(MediaDraft::getSubmissionDeadline).or().le(MediaDraft::getSubmissionDeadline, cutoff))
                    .orderByAsc(MediaDraft::getId).last("LIMIT " + properties.getBatchSize()));
            if (page.isEmpty()) return;
            cursor = page.get(page.size() - 1).getId();
            for (var draft : page) {
                if (expired(draft)) fail(draft, "草稿已过期，请重新上传", "expired");
                else fail(draft, "发布超时，请重试或取消后编辑草稿", "deadline");
            }
        }
    }

    private void advance(MediaDraft draft) {
        if (expired(draft)) {
            fail(draft, "草稿已过期，请重新上传", "expired");
        } else if (deadlineExceeded(draft)) {
            fail(draft, "发布超时，请重试或取消后编辑草稿", "deadline");
        } else if (!Objects.equals(draft.getRevision(), draft.getSubmissionRevision())) {
            fail(draft, "草稿版本已变化，请取消发布后重新提交", "version");
        } else if (draft.getStatus() == MediaDraftStatusEnum.FAILED) {
            fail(draft, "媒体准备失败，请重试或取消发布后编辑草稿", "preparation");
        } else if (draft.getStatus() == MediaDraftStatusEnum.READY) {
            submissions.submitRequested(draft.getId(), draft.getUserId(), draft.getRevision(), draft.getSubmissionAfter());
        } else {
            rows.update(null, claimed(draft).set(MediaDraft::getSubmissionAfter, now().plusSeconds(properties.getWaitingSeconds())));
        }
    }

    private void fail(MediaDraft draft, String message, String reason) {
        int changed = observation.update(draft.getId(), claimed(draft).set(MediaDraft::getSubmissionState, DraftSubmissionState.FAILED)
                .set(MediaDraft::getSubmissionError, message).set(MediaDraft::getSubmissionAfter, null)
                .set(MediaDraft::getSubmissionDeadline, null));
        if (changed > 0) metrics.counter(MetricNames.PUBLICATION_FAILED_TOTAL, "reason", reason).increment();
    }

    private LambdaUpdateWrapper<MediaDraft> claimed(MediaDraft draft) {
        return version(draft).eq(MediaDraft::getSubmissionState, DraftSubmissionState.PENDING)
                .eq(draft.getSubmissionAfter() != null, MediaDraft::getSubmissionAfter, draft.getSubmissionAfter())
                .isNull(draft.getSubmissionAfter() == null, MediaDraft::getSubmissionAfter)
                .eq(draft.getSubmissionDeadline() != null, MediaDraft::getSubmissionDeadline, draft.getSubmissionDeadline())
                .isNull(draft.getSubmissionDeadline() == null, MediaDraft::getSubmissionDeadline)
                .ne(MediaDraft::getStatus, MediaDraftStatusEnum.SUBMITTED);
    }

    private LambdaUpdateWrapper<MediaDraft> version(MediaDraft draft) {
        return new LambdaUpdateWrapper<MediaDraft>().eq(MediaDraft::getId, draft.getId())
                .eq(MediaDraft::getUserId, draft.getUserId()).eq(MediaDraft::getRevision, draft.getRevision());
    }

    private MediaDraft owned(Long id, Long userId) {
        var draft = rows.selectById(id);
        if (draft == null || !userId.equals(draft.getUserId())) throw new BizException(ErrorCode.NOT_FOUND, "草稿不存在");
        return draft;
    }

    private Receipt receipt(MediaDraft draft) {
        if (draft.getStatus() == MediaDraftStatusEnum.SUBMITTED) {
            var result = submissions.receipt(draft.getId());
            return new Receipt(draft.getId(), draft.getSubmissionRevision(), draft.getRevision(), DraftSubmissionState.SUBMITTED.getCode(), MediaDraftStatusEnum.SUBMITTED.getCode(), result.contentId(), result.pipelineId());
        }
        return new Receipt(draft.getId(), draft.getSubmissionRevision(), draft.getRevision(), draft.getSubmissionState().getCode(), draft.getStatus().getCode(), null, null);
    }

    private boolean deadlineExceeded(MediaDraft draft) {
        return draft.getSubmissionDeadline() == null || !draft.getSubmissionDeadline().isAfter(now());
    }

    private boolean expired(MediaDraft draft) { return draft.expiredAt(LocalDateTime.now()); }

    private boolean businessRejection(Exception error) {
        if (!(error instanceof BizException business)) return false;
        return switch (business.getErrorCode()) {
            case INVALID_ARGUMENT, INVALID_REQUEST, FORBIDDEN, NOT_FOUND,
                    DRAFT_PUBLICATION_LOCKED, DRAFT_PREPARATION_FAILED, DRAFT_VERSION_CHANGED,
                    DRAFT_EXPIRED, DRAFT_ALREADY_SUBMITTED -> true;
            default -> false;
        };
    }

    private void requireCurrentVersion(MediaDraft draft, Integer revision) {
        if (expired(draft)) throw new BizException(ErrorCode.DRAFT_EXPIRED);
        if (!Objects.equals(draft.getRevision(), revision)) throw new BizException(ErrorCode.DRAFT_VERSION_CHANGED);
    }

    private void requireAcceptable(MediaDraft draft, Integer revision) {
        requireCurrentVersion(draft, revision);
        if (draft.getStatus() == MediaDraftStatusEnum.FAILED) throw new BizException(ErrorCode.DRAFT_PREPARATION_FAILED);
        if (draft.getStatus() == MediaDraftStatusEnum.SUBMITTED) throw new BizException(ErrorCode.DRAFT_ALREADY_SUBMITTED);
        if (!ACTIVE.contains(draft.getStatus())) throw conflict();
    }

    private static LocalDateTime now() { return LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS); }
    private BizException conflict() { return new BizException(ErrorCode.STATE_CONFLICT, "草稿版本或发布状态已变化，请刷新"); }
}
