package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.search.api.AudioSearchPort;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioPage;
import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts;

import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.media.MediaInputs;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.support.ContentSubmissionRules;
import cn.diaojiahong.myyoutube.notification.support.ReviewFeedback;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.video.config.AudioProcessingProperties;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.enums.*;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioVO;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import cn.diaojiahong.myyoutube.video.service.AudioIndexChanges;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AudioServiceImpl implements IAudioService {
    public static final String VISIBILITY_PUBLIC = "public";
    public static final String VISIBILITY_PRIVATE = "private";
    private static final long SIGN_SECONDS = 1200;
    private final AudioMapper audioMapper;
    private final S3StorageUtil storageUtil;
    private final UserPort users;
    private final ContentSubmissionRules rules;
    private final AudioProcessingProperties processing;
    private final AudioSearchPort search;

    @Override public String publish(AudioPublishDTO dto) {
        Long userId = getUserId();
        if (dto == null || dto.getRequestId() == null || !dto.getRequestId().matches("[A-Za-z0-9_-]{1,64}"))
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "请提供有效的音频上传请求标识");
        String source = ownedUpload(dto.getObjectKey(), "audio", userId);
        String cover = StringUtils.hasText(dto.getCoverObjectKey()) ? ownedUpload(dto.getCoverObjectKey(), "picture", userId) : null;
        String requestedTitle = StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : null;
        String fingerprint = fingerprint(source, cover, requestedTitle);
        var existing = request(userId, dto.getRequestId());
        if (existing != null) return replay(existing, fingerprint);
        String title = rules.title(requestedTitle == null ? defaultTitle(userId) : requestedTitle);
        var now = LocalDateTime.now();
        var audio = new Audio().setUploaderId(userId).setTitle(title).setRequestId(dto.getRequestId()).setRequestFingerprint(fingerprint)
                .setSourceObjectKey(source).setSourceCoverObjectKey(cover).setVisibility(VISIBILITY_PUBLIC).setIsDelete(0)
                .setProcessingStatus(AudioProcessingStatus.PROCESSING).setReviewStatus(AudioReviewStatus.PENDING)
                .setPreparationAttempt(0).setPreparationRetry(false).setPreparationAfter(now)
                .setProcessingDeadline(now.plusMinutes(processing.getDeadlineMinutes()));
        try { audioMapper.insert(audio); }
        catch (DuplicateKeyException e) {
            existing = request(userId, dto.getRequestId());
            if (existing == null) throw e;
            return replay(existing, fingerprint);
        }
        return audio.getId().toString();
    }

    @Override public AudioVO getMine(Long id) { return views(List.of(owned(id, getUserId())), true).get(0); }

    @Override public boolean retry(Long id) {
        var audio = owned(id, getUserId());
        if (audio.getReviewStatus() != AudioReviewStatus.PENDING) return false;
        if (audio.getProcessingStatus() == AudioProcessingStatus.PROCESSING) return true;
        if (audio.getProcessingStatus() != AudioProcessingStatus.FAILED && audio.getAnalysisStatus() != AudioAnalysisStatus.FAILED) return false;
        var now = LocalDateTime.now();
        if (audio.getProcessingStatus() == AudioProcessingStatus.READY && audio.getAnalysisStatus() == AudioAnalysisStatus.FAILED
                && audio.getReviewStatus() == AudioReviewStatus.PENDING)
            return audioMapper.update(null, new LambdaUpdateWrapper<Audio>().eq(Audio::getId, id).eq(Audio::getReviewStatus, AudioReviewStatus.PENDING)
                    .eq(Audio::getIsDelete, 0).eq(Audio::getAnalysisStatus, AudioAnalysisStatus.FAILED)
                    .set(Audio::getAnalysisStatus, audio.getTranscriptKey() == null ? AudioAnalysisStatus.RECOGNIZING : AudioAnalysisStatus.SCREENING)
                    .set(Audio::getPreparationRetry, true).set(Audio::getPreparationAfter, now)
                    .set(Audio::getProcessingDeadline, now.plusMinutes(processing.getDeadlineMinutes())).set(Audio::getErrorMessage, null)) > 0;
        return audioMapper.update(null, new LambdaUpdateWrapper<Audio>().eq(Audio::getId, id).eq(Audio::getUploaderId, audio.getUploaderId())
                .eq(Audio::getIsDelete, 0).eq(Audio::getReviewStatus, AudioReviewStatus.PENDING)
                .eq(Audio::getProcessingStatus, AudioProcessingStatus.FAILED).eq(Audio::getPreparationAttempt, audio.getPreparationAttempt())
                .set(Audio::getProcessingStatus, AudioProcessingStatus.PROCESSING).set(Audio::getPreparationRetry, true)
                .set(Audio::getPreparationAfter, now).set(Audio::getProcessingDeadline, now.plusMinutes(processing.getDeadlineMinutes()))
                .set(Audio::getErrorMessage, null)) > 0;
    }

    @Override public PageResult<AudioVO> myAudios(PageQuery pageQuery) {
        var page = audioMapper.selectPage(pageQuery.toPage(), new LambdaQueryWrapper<Audio>()
                .eq(Audio::getUploaderId, getUserId()).eq(Audio::getIsDelete, 0)
                .orderByDesc(Audio::getCreateTime).orderByDesc(Audio::getId));
        return PageResult.of(page.getTotal(), page.getPages(), views(page.getRecords(), true));
    }

    @Override public AudioPage library(String keyword, PageQuery pageQuery) {
        return search(keyword, pageQuery, null);
    }
    @Override public AudioPage searchMine(String keyword, PageQuery pageQuery) {
        return search(keyword, pageQuery, getUserId());
    }
    private AudioPage search(String keyword, PageQuery page, Long owner) {
        var query = new AudioDiscoveryContracts.Query(keyword, owner, page.getPageNo(), page.getPageSize());
        query.validate();
        var slice = search.search(query);
        if (slice == null || slice.ids() == null) throw new IllegalStateException("Missing audio search result");
        var found = slice.ids().isEmpty() ? Map.<Long, Audio>of() : audioMapper.selectBatchIds(slice.ids()).stream()
                .collect(Collectors.toMap(Audio::getId, a -> a));
        var visible = slice.ids().stream().distinct().map(found::get).filter(Objects::nonNull).filter(a -> a.getIsDelete() == 0)
                .filter(a -> owner != null ? owner.equals(a.getUploaderId()) : publiclyVisible(a)).toList();
        return new AudioPage(views(visible, owner != null), slice.hasMore());
    }
    @Override public AudioVO detail(Long id) {
        var audio = audioMapper.selectById(id);
        if (audio == null || !publiclyVisible(audio)) throw new BizException(ErrorCode.NOT_FOUND, "音频不存在或不可访问");
        return views(List.of(audio), false).get(0);
    }
    private boolean publiclyVisible(Audio audio) {
        return audio.getIsDelete() == 0 && audio.getProcessingStatus() == AudioProcessingStatus.READY
                && audio.getReviewStatus() == AudioReviewStatus.APPROVED && VISIBILITY_PUBLIC.equals(audio.getVisibility());
    }

    @Override public void setVisibility(Long id, String visibility) {
        var audio = owned(id, getUserId());
        if (!VISIBILITY_PUBLIC.equals(visibility) && !VISIBILITY_PRIVATE.equals(visibility))
            throw new BizException(ErrorCode.INVALID_ARGUMENT, "可见性参数无效");
        if (audio.getProcessingStatus() != AudioProcessingStatus.READY || audio.getReviewStatus() != AudioReviewStatus.APPROVED)
            throw new BizException(ErrorCode.STATE_CONFLICT, "审核通过后才可调整音频可见性");
        if (audioMapper.update(null, AudioIndexChanges.bump(new LambdaUpdateWrapper<Audio>()).eq(Audio::getId, id).eq(Audio::getUploaderId, audio.getUploaderId())
                .eq(Audio::getIsDelete, 0).eq(Audio::getProcessingStatus, AudioProcessingStatus.READY)
                .eq(Audio::getReviewStatus, AudioReviewStatus.APPROVED).set(Audio::getVisibility, visibility)) == 0)
            throw new BizException(ErrorCode.STATE_CONFLICT, "音频状态已变化，请刷新后重试");
    }

    @Override public void delete(Long id) {
        var audio = owned(id, getUserId());
        audioMapper.update(null, AudioIndexChanges.bump(new LambdaUpdateWrapper<Audio>()).eq(Audio::getId, id).eq(Audio::getUploaderId, audio.getUploaderId())
                .eq(Audio::getIsDelete, 0).set(Audio::getIsDelete, 1).set(Audio::getPreparationAfter, null));
    }

    @Override public Audio getReferenceable(Long audioId, Long userId) {
        var audio = audioMapper.selectById(audioId);
        if (audio == null || !Integer.valueOf(0).equals(audio.getIsDelete()))
            throw new BizException(ErrorCode.NOT_FOUND, "音频不存在或已被删除");
        if (audio.getProcessingStatus() != AudioProcessingStatus.READY || audio.getReviewStatus() != AudioReviewStatus.APPROVED)
            throw new BizException(ErrorCode.STATE_CONFLICT, "音频尚未通过审核或已不可用");
        if (!VISIBILITY_PUBLIC.equals(audio.getVisibility()) && !Objects.equals(audio.getUploaderId(), userId))
            throw new BizException(ErrorCode.FORBIDDEN, "无权使用该音频");
        return audio;
    }

    private List<AudioVO> views(List<Audio> rows, boolean owner) {
        if (rows.isEmpty()) return List.of();
        var ids = rows.stream().filter(a -> a.getCoverObjectKey() == null).map(Audio::getUploaderId).distinct().toList();
        List<UserSummary> summaries = ids.isEmpty() ? List.of() : users.listByIds(ids);
        var avatars = summaries == null ? Map.<Long, String>of() : summaries.stream()
                .filter(u -> u != null && u.getId() != null && StringUtils.hasText(u.getAvatarUrl()))
                .collect(Collectors.toMap(UserSummary::getId, UserSummary::getAvatarUrl, (a, b) -> a));
        return rows.stream().map(audio -> {
            var vo = toVO(audio, owner);
            String key = audio.getCoverObjectKey() == null ? avatars.get(audio.getUploaderId()) : audio.getCoverObjectKey();
            if (StringUtils.hasText(key) && audio.getReviewStatus() != AudioReviewStatus.BANNED) vo.setCoverUrl(storageUtil.signGetUrl(key, SIGN_SECONDS));
            return vo;
        }).toList();
    }

    private AudioVO toVO(Audio audio, boolean owner) {
        var vo = new AudioVO();
        vo.setId(audio.getId().toString()); vo.setTitle(audio.getTitle()); vo.setUploaderId(audio.getUploaderId().toString());
        vo.setDurationMs(audio.getDurationMs()); vo.setFileSize(audio.getFileSize()); vo.setMime(audio.getMime());
        vo.setProcessingStatus(audio.getProcessingStatus()); vo.setReviewStatus(audio.getReviewStatus());
        if (owner) {
            vo.setAnalysisStatus(audio.getAnalysisStatus()); vo.setErrorMessage(audio.getErrorMessage());
            if (audio.getReviewStatus() != AudioReviewStatus.PENDING)
                vo.setReviewReason(ReviewFeedback.message("音频", audio.getReviewStatus() == AudioReviewStatus.APPROVED));
        }
        vo.setVisibility(audio.getVisibility()); vo.setCreateTime(audio.getCreateTime());
        if (audio.getProcessingStatus() == AudioProcessingStatus.READY && audio.getReviewStatus() != AudioReviewStatus.BANNED
                && StringUtils.hasText(audio.getObjectKey())) vo.setPreviewUrl(storageUtil.signGetUrl(audio.getObjectKey(), SIGN_SECONDS));
        return vo;
    }

    private Audio owned(Long id, Long userId) {
        var audio = audioMapper.selectById(id);
        if (audio == null || !Integer.valueOf(0).equals(audio.getIsDelete()) || !Objects.equals(audio.getUploaderId(), userId))
            throw new BizException(ErrorCode.NOT_FOUND, "音频不存在");
        return audio;
    }

    private Audio request(Long userId, String requestId) {
        return audioMapper.selectOne(new LambdaQueryWrapper<Audio>().eq(Audio::getUploaderId, userId).eq(Audio::getRequestId, requestId));
    }

    private String replay(Audio existing, String fingerprint) {
        if (!fingerprint.equals(existing.getRequestFingerprint()) || !Integer.valueOf(0).equals(existing.getIsDelete()))
            throw new BizException(ErrorCode.STATE_CONFLICT, "该上传请求已受理，请勿更换参数或重复使用请求标识");
        return existing.getId().toString();
    }

    private String defaultTitle(Long userId) {
        var list = users.listByIds(List.of(userId));
        String nickname = list == null ? null : list.stream().filter(u -> u != null && userId.equals(u.getId()))
                .map(UserSummary::getNickname).filter(StringUtils::hasText).findFirst().orElse(null);
        return (nickname == null ? "用户" + userId : nickname.trim()) + " 的音频";
    }

    private static String ownedUpload(String value, String directory, Long userId) {
        if (!StringUtils.hasText(value) || value.length() > 512 || !value.equals(value.trim())) throw new BizException(ErrorCode.INVALID_ARGUMENT, "上传文件标识无效");
        try { MediaInputs.objectKey(value); }
        catch (IllegalArgumentException e) { throw new BizException(ErrorCode.INVALID_ARGUMENT, "上传文件标识无效"); }
        if (!value.startsWith("uploads/" + directory + "/" + userId + "/") || value.contains("//"))
            throw new BizException(ErrorCode.FORBIDDEN, "无权使用该上传文件");
        return value;
    }

    private static String fingerprint(String source, String cover, String title) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(JsonUtil.toJson(Arrays.asList(source, cover, title)).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static Long getUserId() {
        String value = UserContext.getUserId();
        if (value == null) throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        return Long.valueOf(value);
    }
}
