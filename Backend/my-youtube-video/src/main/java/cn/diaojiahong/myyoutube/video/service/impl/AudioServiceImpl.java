package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.video.domain.dto.AudioPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.vo.AudioVO;
import cn.diaojiahong.myyoutube.video.mapper.AudioMapper;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioServiceImpl implements IAudioService {

    public static final String VISIBILITY_PUBLIC = "public";
    public static final String VISIBILITY_PRIVATE = "private";
    private static final long AUDIO_MAX_DURATION_MS = 10 * 60_000L;
    private static final long SIGN_SECONDS = 3600L * 2;

    private final AudioMapper audioMapper;
    private final S3StorageUtil storageUtil;

    @Override
    public String publish(AudioPublishDTO dto) {
        Long userId = getUserId();
        if (dto == null || !StringUtils.hasText(dto.getObjectKey())) {

            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频文件不能为空");
        }
        if (dto.getDurationMs() == null || dto.getDurationMs() <= 0) {

            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频时长无效");
        }
        if (dto.getDurationMs() > AUDIO_MAX_DURATION_MS) {

            throw new BizException(ErrorCode.INVALID_ARGUMENT, "音频时长不能超过 10 分钟");
        }
        String objectKey = storageUtil.normalizeToObjectKey(dto.getObjectKey().trim());
        if (!objectKeyBelongsToUser(userId, objectKey)) {

            throw new BizException(ErrorCode.FORBIDDEN, "无权使用该音频文件");
        }
        String visibility = VISIBILITY_PRIVATE.equals(dto.getVisibility()) ? VISIBILITY_PRIVATE : VISIBILITY_PUBLIC;
        Audio audio = new Audio();
        audio.setUploaderId(userId);
        audio.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "未命名音频");
        audio.setObjectKey(objectKey);
        audio.setDurationMs(dto.getDurationMs().intValue());
        audio.setVisibility(visibility);
        audio.setIsDelete(0);
        audioMapper.insert(audio);
        return String.valueOf(audio.getId());
    }

    @Override
    public PageResult<AudioVO> myAudios(PageQuery pageQuery) {
        Long userId = getUserId();
        Page<Audio> page = audioMapper.selectPage(pageQuery.toPage(),
                new LambdaQueryWrapper<Audio>()
                        .eq(Audio::getUploaderId, userId)
                        .eq(Audio::getIsDelete, 0)
                        .orderByDesc(Audio::getCreateTime));
        return PageResult.of(page.getTotal(), page.getPages(),
                page.getRecords().stream().map(a -> toVO(a, true)).toList());
    }

    @Override
    public PageResult<AudioVO> library(String keyword, PageQuery pageQuery) {
        LambdaQueryWrapper<Audio> wrapper = new LambdaQueryWrapper<Audio>()
                .eq(Audio::getVisibility, VISIBILITY_PUBLIC)
                .eq(Audio::getIsDelete, 0);
        if (StringUtils.hasText(keyword)) {

            wrapper.like(Audio::getTitle, keyword.trim());
        }
        Page<Audio> page = audioMapper.selectPage(pageQuery.toPage(), wrapper.orderByDesc(Audio::getCreateTime));
        return PageResult.of(page.getTotal(), page.getPages(),
                page.getRecords().stream().map(a -> toVO(a, true)).toList());
    }

    @Override
    public void setVisibility(Long id, String visibility) {
        Long userId = getUserId();
        Audio audio = audioMapper.selectById(id);
        if (audio == null || audio.getIsDelete() == 1 || !audio.getUploaderId().equals(userId)) {

            throw new BizException(ErrorCode.NOT_FOUND, "音频不存在");
        }
        if (!VISIBILITY_PUBLIC.equals(visibility) && !VISIBILITY_PRIVATE.equals(visibility)) {

            throw new BizException(ErrorCode.INVALID_ARGUMENT, "可见性参数无效");
        }
        // 在先使用权: 转私密只影响库内可发现性与新增引用, 已发布动态持快照不受影响
        Audio patch = new Audio();
        patch.setVisibility(visibility);
        audioMapper.update(patch, new LambdaQueryWrapper<Audio>().eq(Audio::getId, id));
    }

    @Override
    public void delete(Long id) {
        Long userId = getUserId();
        Audio audio = audioMapper.selectById(id);
        if (audio == null || audio.getIsDelete() == 1 || !audio.getUploaderId().equals(userId)) {

            throw new BizException(ErrorCode.NOT_FOUND, "音频不存在");
        }
        // 快照机制保证已引用动态不受影响, 直接软删库条目即可
        Audio patch = new Audio();
        patch.setIsDelete(1);
        audioMapper.update(patch, new LambdaQueryWrapper<Audio>().eq(Audio::getId, id));
    }

    @Override
    public Audio getReferenceable(Long audioId, Long userId) {
        Audio audio = audioMapper.selectById(audioId);
        if (audio == null || audio.getIsDelete() == 1) {

            throw new BizException(ErrorCode.NOT_FOUND, "音频不存在或已被删除");
        }
        if (!VISIBILITY_PUBLIC.equals(audio.getVisibility()) && !audio.getUploaderId().equals(userId)) {

            throw new BizException(ErrorCode.FORBIDDEN, "无权使用该音频");
        }
        return audio;
    }

    @Override
    public AudioVO toVO(Audio audio, boolean withUrl) {

        AudioVO vo = new AudioVO();
        vo.setId(String.valueOf(audio.getId()));
        vo.setTitle(audio.getTitle());
        vo.setDurationMs(audio.getDurationMs());
        vo.setVisibility(audio.getVisibility());
        vo.setUploaderId(String.valueOf(audio.getUploaderId()));
        vo.setCreateTime(audio.getCreateTime());
        if (withUrl && StringUtils.hasText(audio.getObjectKey())) {

            vo.setPreviewUrl(storageUtil.signGetUrl(audio.getObjectKey(), SIGN_SECONDS));
        }
        return vo;
    }

    private boolean objectKeyBelongsToUser(Long userId, String objectKey) {

        if (!StringUtils.hasText(objectKey)) {

            return false;
        }
        String k = objectKey.startsWith("uploads/") ? objectKey.trim() : storageUtil.normalizeToObjectKey(objectKey);
        return k.startsWith("uploads/") && k.contains("/" + userId + "/");
    }

    private static Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(ErrorCode.UNAUTHORIZED, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
