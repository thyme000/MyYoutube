package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.result.Result;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.transcode.api.TranscodePort;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeItemDTO;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.recommend.service.IContentTagService;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.message.ContentDocMessage;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Audio;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicDownloadVO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicVO;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.service.IAudioService;
import cn.diaojiahong.myyoutube.video.service.IDynamicService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DynamicServiceImpl implements IDynamicService {

    private static final int MAX_IMAGES = 18;
    private static final int MIN_IMAGES = 1;
    private static final long DEFAULT_ITEM_DURATION_MS = 3000L;
    private static final long FADE_DURATION_MS = 800L;
    private static final long MIN_ITEM_DURATION_MS = FADE_DURATION_MS + 300L;
    private static final long MEDIA_SIGN_SECONDS = 3600L * 2;
    private static final long DOWNLOAD_SIGN_SECONDS = 3600L;
    private static final long AUDIO_MAX_DURATION_MS = 60_000L;

    private final DynamicMapper dynamicMapper;
    private final ContentMapper contentMapper;
    private final IContentTagService contentTagService;
    private final IContentService contentService;
    private final RocketMQTemplate rocketMQTemplate;
    private final S3StorageUtil storageUtil;
    private final ObjectMapper objectMapper;
    private final UserPort userPort;
    private final TranscodePort transcodePort;
    private final IAudioService audioService;
    private final ContentDocPort contentDocPort;

    @Override
    @Transactional
    public Result<String> publish(DynamicPublishDTO dto) {
        if (dto == null || dto.getItems() == null || dto.getItems().isEmpty()) {
            return Result.fail("图片列表不能为空");
        }
        if (dto.getItems().isEmpty() || dto.getItems().size() > MAX_IMAGES) {
            return Result.fail("图片数量需在 " + MIN_IMAGES + "~" + MAX_IMAGES + " 张之间");
        }
        Long userId = getUserId();

        String canvasType = "horizontal".equals(dto.getCanvasType()) ? "horizontal" : "vertical";
        int canvasW = "horizontal".equals(canvasType) ? 1920 : 1080;
        int canvasH = "horizontal".equals(canvasType) ? 1080 : 1920;
        boolean isFade = !"cut".equals(dto.getTransition());
        String transition = isFade ? "fade" : "cut";
        long fade = isFade ? FADE_DURATION_MS : 0L;

        long audioMs = 0L;
        String audioKey = "";
        String audioTitle = "";
        Long audioId = null;
        if (dto.getAudioId() == null) {
            return Result.fail("请选择背景音乐");
        }
        // 音频库引用校验: 公开或本人私有才可用; objectKey/时长/标题作为快照落库,
        // 之后音频转私密/删除/改名均不影响已发布动态(在先使用权)
        Result<Audio> audioResult = audioService.getReferenceable(dto.getAudioId(), userId);
        if (audioResult.getCode() != 200 || audioResult.getData() == null) {
            return Result.fail(audioResult.getMessage());
        }
        Audio audio = audioResult.getData();
        audioId = audio.getId();
        audioKey = audio.getObjectKey();
        audioTitle = audio.getTitle();
        if (audio.getDurationMs() != null && audio.getDurationMs() > 0) {
            audioMs = Math.min(audio.getDurationMs(), AUDIO_MAX_DURATION_MS);
        }

        int n = dto.getItems().size();
        long uniform = uniformDurationMs(n, audioMs, fade);
        long[] durations = new long[n];
        List<String> imageKeys = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            DynamicPublishDTO.DynamicItemDTO item = dto.getItems().get(i);
            if (item == null || !StringUtils.hasText(item.getImageObjectKey())) {
                return Result.fail("图片列表存在空项");
            }
            String key = storageUtil.normalizeToObjectKey(item.getImageObjectKey().trim());
            if (!objectKeyBelongsToUser(userId, key)) {
                return Result.fail("存在无权使用的图片资源");
            }
            imageKeys.add(key);
            if (item.getDurationMs() != null && item.getDurationMs() > 0) {
                durations[i] = Math.max(item.getDurationMs(), MIN_ITEM_DURATION_MS);
            } else {
                durations[i] = uniform;
            }
        }

        // 切换点与总时长: 第 k 次切换发生在 sum(d_0..d_{k-1}) - k*fade, 预览端按同一公式换算
        List<Map<String, Object>> timeline = new ArrayList<>(n);
        long startMs = 0L;
        long sumDurations = 0L;
        for (int i = 0; i < n; i++) {
            if (i > 0) {
                startMs = sumDurations - (long) i * fade;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("imageObjectKey", imageKeys.get(i));
            entry.put("startMs", startMs);
            entry.put("durationMs", durations[i]);
            entry.put("focusX", clampRatio(dto.getItems().get(i).getFocusX()));
            entry.put("focusY", clampRatio(dto.getItems().get(i).getFocusY()));
            timeline.add(entry);
            sumDurations += durations[i];
        }
        long total = sumDurations - (long) (n - 1) * fade;
        if (audioMs > total) {
            total = audioMs;
        }

        Dynamic dynamic = new Dynamic();
        dynamic.setUserId(userId);
        dynamic.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "");
        dynamic.setCanvasType(canvasType);
        dynamic.setCanvasW(canvasW);
        dynamic.setCanvasH(canvasH);
        dynamic.setTransition(transition);
        dynamic.setFadeMs((int) fade);
        dynamic.setAudioId(audioId);
        dynamic.setAudioObjectKey(audioKey);
        dynamic.setAudioTitle(audioTitle);
        dynamic.setAudioDurationMs((int) audioMs);
        dynamic.setTotalDurationMs((int) total);
        try {
            dynamic.setTimelineJson(objectMapper.writeValueAsString(timeline));
        } catch (Exception e) {
            log.warn("dynamic 序列化时间轴失败", e);
            return Result.fail("发布失败");
        }
        dynamic.setIsDelete(0);
        dynamic.setCreateTime(java.time.LocalDateTime.now());
        dynamic.setTags(StringUtils.hasText(dto.getTags()) ? dto.getTags().trim() : null);
        dynamic.setVisibility("public");
        dynamic.setAllowComment(dto.getAllowComment() != null && dto.getAllowComment() == 0 ? 0 : 1);
        // content 池先行: 预分配雪花id, 动态表主键与之相同
        Content content = new Content()
                .setId(IdWorker.getId())
                .setType(ContentTypeEnum.DYNAMIC)
                .setAuthorId(userId)
                .setStatus(VideoStatusEnum.PUBLISHED.getCode())
                .setVisibility("public")
                .setCreateTime(dynamic.getCreateTime());
        dynamic.setId(content.getId());
        contentMapper.insert(content);
        dynamicMapper.insert(dynamic);
        // 可选标签入库, 并延迟同步 ES content 文档(首帧作封面)
        List<Long> tagIds = contentTagService.saveTags(dynamic.getTags(), content.getId());
        rocketMQTemplate.syncSendDelayTimeSeconds(
                MqConstants.TOPIC_ES_DOCUMENT + ":" + MqConstants.TAG_ES_CONTENT,
                ContentDocMessage.builder()
                        .type(ContentTypeEnum.DYNAMIC.getCode()).contentId(content.getId())
                        .title(dynamic.getTitle())
                        .coverUrl(imageKeys.get(0))
                        .tags(dynamic.getTags())
                        .statusCode(VideoStatusEnum.PUBLISHED.getCode())
                        .visibilityCode("public")
                        .authorId(userId)
                        .createTime(dynamic.getCreateTime()).tagIds(tagIds)
                        .build(), 5);
        return Result.success(String.valueOf(dynamic.getId()));
    }

    @Override
    public Result<PageResult<DynamicVO>> feed(PageQuery pageQuery) {
        Page<Dynamic> page = dynamicMapper.selectPage(pageQuery.toPage(),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Dynamic>()
                        .eq(Dynamic::getIsDelete, 0)
                        .eq(Dynamic::getVisibility, "public")
                        .orderByDesc(Dynamic::getCreateTime));
        List<DynamicVO> vos = toVOs(page.getRecords());
        return Result.success(PageResult.of(page.getTotal(), page.getPages(), vos));
    }

    @Override
    public Result<DynamicVO> detail(Long id) {
        Dynamic dynamic = dynamicMapper.selectById(id);
        if (dynamic == null || dynamic.getIsDelete() != null && dynamic.getIsDelete() == 1) {
            return Result.fail("动态不存在");
        }
        // 私密动态仅作者本人可见
        if ("private".equals(dynamic.getVisibility())) {
            String uid = UserContext.getUserId();
            if (uid == null || !uid.equals(String.valueOf(dynamic.getUserId()))) {
                return Result.fail("无权查看该动态");
            }
        }
        List<DynamicVO> vos = toVOs(List.of(dynamic));
        DynamicVO vo = vos.get(0);
        // 详情附互动计数(来自 content 池)
        Content c = contentService.getById(id);
        if (c != null) {
            vo.setLikeCount(c.getLikeCount());
            vo.setCommentCount(c.getCommentCount());
        }
        return Result.success(vo);
    }

    @Override
    public Result<DynamicDownloadVO> requestDownload(Long dynamicId) {
        Dynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null || dynamic.getIsDelete() != null && dynamic.getIsDelete() == 1) {
            return Result.fail("动态不存在");
        }
        TranscodeSubmitDTO submit = buildSubmitDTO(dynamic);
        // 任务归属请求者: SSE 通知与状态查询权限都按这个 id
        submit.setUserId(getUserId());
        TranscodeTaskVO task;
        try {
            task = transcodePort.submitTask(submit);
        } catch (Exception e) {
            log.error("提交转码任务失败 dynamicId={}", dynamicId, e);
            return Result.fail("转码服务暂不可用，请稍后再试");
        }
        if (task == null || task.getTaskId() == null) {
            return Result.fail("创建下载任务失败");
        }
        return Result.success(toDownloadVO(task));
    }

    @Override
    public Result<DynamicDownloadVO> getDownloadStatus(Long taskId) {
        Long userId = getUserId();
        TranscodeTaskVO task;
        try {
            task = transcodePort.getTask(taskId);
        } catch (Exception e) {
            log.error("查询转码任务失败 taskId={}", taskId, e);
            return Result.fail("转码服务暂不可用，请稍后再试");
        }
        if (task == null || task.getTaskId() == null) {
            return Result.fail("任务不存在");
        }
        if (!Objects.equals(task.getUserId(), userId)) {
            return Result.fail("无权查看该任务");
        }
        return Result.success(toDownloadVO(task));
    }

    @Override
    @Transactional
    public Result<Void> deleteDynamic(Long id) {
        Long userId = getUserId();
        boolean success = dynamicMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Dynamic>()
                        .eq(Dynamic::getId, id)
                        .eq(Dynamic::getUserId, userId)
                        .set(Dynamic::getIsDelete, 1)) > 0;
        if (!success) {
            return Result.fail("删除失败,请稍后再试");
        }
        // content 池无删除标记, 不同步摘除的话池分发与创作中心会把已删动态查回来
        contentService.removeById(id);
        // ES 尽力删除, 失败不影响 DB 删除
        try {
            contentDocPort.deleteDoc(id);
        } catch (Exception e) {
            log.warn("ES 动态文档删除失败, dynamicId={}", id, e);
        }
        return Result.success();
    }

    @Override
    public Result<Void> privateDynamic(Long id) {
        return switchVisibility(id, "private");
    }

    @Override
    public Result<Void> publicDynamic(Long id) {
        return switchVisibility(id, "public");
    }

    private Result<Void> switchVisibility(Long id, String visibility) {
        Long userId = getUserId();
        Dynamic dynamic = dynamicMapper.selectById(id);
        if (dynamic == null || !userId.equals(dynamic.getUserId())) {
            return Result.fail("动态不存在");
        }
        boolean success = dynamicMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Dynamic>()
                        .eq(Dynamic::getId, id)
                        .eq(Dynamic::getUserId, userId)
                        .set(Dynamic::getVisibility, visibility)) > 0;
        if (!success) {
            return Result.fail("修改失败");
        }
        // content 池随领域状态机同步
        contentService.lambdaUpdate()
                .eq(Content::getId, id)
                .set(Content::getVisibility, visibility)
                .update();
        // ES 同步: 私密清空 suggest 防搜索联想泄露, 公开按动态字段重建
        try {
            if ("private".equals(visibility)) {
                contentDocPort.syncVisibility(id, new VisibilitySync(null, null, null, "private", false));
            } else {
                // 动态无简介字段, suggest 重建时以空串占位(List.of 不允许 null 元素)
                contentDocPort.syncVisibility(id, new VisibilitySync(
                        StringUtils.hasText(dynamic.getTitle()) ? dynamic.getTitle() : "",
                        "", dynamic.getTags(), "public", true));
            }
        } catch (Exception e) {
            log.warn("ES 动态可见性同步失败, dynamicId={}, visibility={}", id, visibility, e);
        }
        return Result.success();
    }

    @Override
    public Result<Void> openComment(Long id) {
        return switchComment(id, 1);
    }

    @Override
    public Result<Void> closeComment(Long id) {
        return switchComment(id, 0);
    }

    private Result<Void> switchComment(Long id, int allow) {
        Long userId = getUserId();
        boolean success = dynamicMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Dynamic>()
                        .eq(Dynamic::getId, id)
                        .eq(Dynamic::getUserId, userId)
                        .set(Dynamic::getAllowComment, allow)) > 0;
        if (!success) {
            return Result.fail("修改失败");
        }
        return Result.success();
    }

    private DynamicDownloadVO toDownloadVO(TranscodeTaskVO task) {
        String url = null;
        if (TranscodeTaskVO.STATUS_DONE.equals(task.getStatus()) && StringUtils.hasText(task.getResultObjectKey())) {
            url = storageUtil.signGetUrl(task.getResultObjectKey(), DOWNLOAD_SIGN_SECONDS);
        }
        return DynamicDownloadVO.builder()
                .taskId(String.valueOf(task.getTaskId()))
                .status(task.getStatus())
                .downloadUrl(url)
                .build();
    }

    private TranscodeSubmitDTO buildSubmitDTO(Dynamic dynamic) {
        TranscodeSubmitDTO submit = new TranscodeSubmitDTO();
        submit.setUserId(dynamic.getUserId());
        submit.setDynamicId(dynamic.getId());
        submit.setCanvasW(dynamic.getCanvasW());
        submit.setCanvasH(dynamic.getCanvasH());
        submit.setTransition(dynamic.getTransition());
        submit.setFadeMs(dynamic.getFadeMs());
        submit.setAudioObjectKey(dynamic.getAudioObjectKey());
        submit.setAudioDurationMs(dynamic.getAudioDurationMs() != null ? dynamic.getAudioDurationMs().longValue() : 0L);
        submit.setTotalDurationMs(dynamic.getTotalDurationMs() != null ? dynamic.getTotalDurationMs().longValue() : 0L);
        List<TranscodeItemDTO> items = new ArrayList<>();
        for (DynamicVO.Item item : parseTimeline(dynamic.getTimelineJson())) {
            TranscodeItemDTO ti = new TranscodeItemDTO();
            ti.setImageObjectKey(item.getImageObjectKey());
            ti.setStartMs(item.getStartMs());
            ti.setDurationMs(item.getDurationMs());
            ti.setFocusX(item.getFocusX());
            ti.setFocusY(item.getFocusY());
            items.add(ti);
        }
        submit.setItems(items);
        return submit;
    }

    private List<DynamicVO.Item> parseTimeline(String timelineJson) {
        try {
            List<Map<String, Object>> raw = objectMapper.readValue(timelineJson,
                    new TypeReference<List<Map<String, Object>>>() {});
            List<DynamicVO.Item> items = new ArrayList<>(raw.size());
            for (Map<String, Object> m : raw) {
                DynamicVO.Item item = new DynamicVO.Item();
                item.setImageObjectKey(String.valueOf(m.get("imageObjectKey")));
                item.setStartMs(toLong(m.get("startMs")));
                item.setDurationMs(toLong(m.get("durationMs")));
                item.setFocusX(toDouble(m.get("focusX")));
                item.setFocusY(toDouble(m.get("focusY")));
                items.add(item);
            }
            return items;
        } catch (Exception e) {
            log.warn("解析时间轴失败: {}", timelineJson, e);
            return List.of();
        }
    }

    private static Long toLong(Object v) {
        // 全局 ObjectMapper 会把 Long 序列化成字符串落库, 这里兼容两种形态
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(v));
    }

    private static Double toDouble(Object v) {
        if (v == null) {
            return null;
        }
        return v instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(v));
    }

    private List<DynamicVO> toVOs(List<Dynamic> dynamics) {
        if (dynamics == null || dynamics.isEmpty()) {
            return List.of();
        }
        List<Long> authorIds = dynamics.stream().map(Dynamic::getUserId).distinct().collect(Collectors.toList());
        Map<Long, String> nicknames = authorIds.isEmpty() ? Map.of() : userPort.getNicknames(authorIds);
        Map<Long, String> avatars = authorIds.isEmpty() ? Map.of() : userPort.getAvatars(authorIds);

        List<DynamicVO> vos = new ArrayList<>(dynamics.size());
        for (Dynamic d : dynamics) {
            DynamicVO vo = new DynamicVO();
            vo.setId(String.valueOf(d.getId()));
            vo.setUserId(String.valueOf(d.getUserId()));
            vo.setAuthorId(String.valueOf(d.getUserId()));
            vo.setTitle(d.getTitle());
            vo.setTags(d.getTags());
            vo.setCanvasType(d.getCanvasType());
            vo.setCanvasW(d.getCanvasW());
            vo.setCanvasH(d.getCanvasH());
            vo.setTransition(d.getTransition());
            vo.setFadeMs(d.getFadeMs());
            vo.setAudioDurationMs(d.getAudioDurationMs());
            vo.setAudioTitle(d.getAudioTitle());
            vo.setTotalDurationMs(d.getTotalDurationMs());
            vo.setVisibility(d.getVisibility());
            vo.setAllowComment(d.getAllowComment());
            vo.setAudioUrl(StringUtils.hasText(d.getAudioObjectKey())
                    ? storageUtil.signGetUrl(d.getAudioObjectKey(), MEDIA_SIGN_SECONDS) : null);
            vo.setAuthorNickname(nicknames.getOrDefault(d.getUserId(), ""));
            String avatarKey = avatars.get(d.getUserId());
            vo.setAuthorAvatarUrl(StringUtils.hasText(avatarKey)
                    ? storageUtil.signGetUrl(avatarKey, MEDIA_SIGN_SECONDS) : "");
            vo.setCreateTime(d.getCreateTime() == null ? null
                    : d.getCreateTime().atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
            List<DynamicVO.Item> items = parseTimeline(d.getTimelineJson());
            for (DynamicVO.Item item : items) {
                if (StringUtils.hasText(item.getImageObjectKey())) {
                    item.setImageUrl(storageUtil.signGetUrl(item.getImageObjectKey(), MEDIA_SIGN_SECONDS));
                }
            }
            vo.setItems(items);
            vos.add(vo);
        }
        return vos;
    }

    /**
     * 均分时长: 成片填满音频时 D = (T + (n-1)*fade) / n, 无音频给固定 3s。
     */
    private static long uniformDurationMs(int n, long audioMs, long fade) {
        if (audioMs > 0) {
            return Math.max((audioMs + (long) (n - 1) * fade) / n, MIN_ITEM_DURATION_MS);
        }
        return DEFAULT_ITEM_DURATION_MS;
    }

    private static double clampRatio(Double v) {
        if (v == null || v.isNaN()) {
            return 0.5;
        }
        return Math.max(0.0, Math.min(1.0, v));
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
            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
