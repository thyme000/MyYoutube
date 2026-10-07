package cn.diaojiahong.myyoutube.video.service.impl;

import cn.diaojiahong.myyoutube.common.constants.MqConstants;
import cn.diaojiahong.myyoutube.common.chain.SyncChainStepRegistry;
import cn.diaojiahong.myyoutube.common.page.PageQuery;
import cn.diaojiahong.myyoutube.common.page.PageResult;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.idempotent.Idempotent;
import cn.diaojiahong.myyoutube.common.mybatis.OwnershipUpdate;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.transcode.api.TranscodePort;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeItemDTO;
import cn.diaojiahong.myyoutube.transcode.api.dto.TranscodeSubmitDTO;
import cn.diaojiahong.myyoutube.transcode.api.vo.TranscodeTaskVO;
import cn.diaojiahong.myyoutube.mq.publisher.AutoTagPublisher;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.mapper.ContentMapper;
import cn.diaojiahong.myyoutube.content.service.IContentService;
import cn.diaojiahong.myyoutube.user.api.UserPort;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummaries;
import cn.diaojiahong.myyoutube.user.api.dto.UserSummary;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
import cn.diaojiahong.myyoutube.video.api.enums.ContentTypeEnum;
import cn.diaojiahong.myyoutube.video.api.enums.VideoStatusEnum;
import cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishChain;
import cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishContext;
import cn.diaojiahong.myyoutube.video.chain.dynamic.DynamicPublishRules;
import cn.diaojiahong.myyoutube.video.domain.dto.DynamicPublishDTO;
import cn.diaojiahong.myyoutube.video.domain.po.Dynamic;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicDownloadVO;
import cn.diaojiahong.myyoutube.video.domain.vo.DynamicVO;
import cn.diaojiahong.myyoutube.video.mapper.DynamicMapper;
import cn.diaojiahong.myyoutube.video.service.IDynamicService;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

    private static final long MEDIA_SIGN_SECONDS = 3600L * 2;
    private static final long DOWNLOAD_SIGN_SECONDS = 3600L;

    private final DynamicMapper dynamicMapper;
    private final ContentMapper contentMapper;
    private final AutoTagPublisher autoTagPublisher;
    private final IContentService contentService;
    private final S3StorageUtil storageUtil;
    private final ObjectMapper objectMapper;
    private final UserPort userPort;
    private final TranscodePort transcodePort;
    private final ContentDocPort contentDocPort;
    private final SyncChainStepRegistry chainRegistry;
    private final TransactionTemplate transactionTemplate;

    @Override
    @Transactional
    @Idempotent(key = "'dynamic:publish:' + #userId + ':' + #dto.hashCode()", ttl = 60)
    public String publish(DynamicPublishDTO dto) {
        // 条目/音频引用/图片归属校验与派生全部在链上, 步骤见 video.chain.dynamic
        DynamicPublishContext ctx = DynamicPublishContext.of(dto, getUserId());
        chainRegistry.execute(DynamicPublishChain.GROUP, ctx);

        List<String> imageKeys = ctx.getImageKeys();
        long[] durations = ctx.getDurations();
        long fade = ctx.getFadeMs();
        long audioMs = ctx.getAudioMs();
        int n = imageKeys.size();

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
            entry.put("focusX", DynamicPublishRules.clampRatio(dto.getItems().get(i).getFocusX()));
            entry.put("focusY", DynamicPublishRules.clampRatio(dto.getItems().get(i).getFocusY()));
            timeline.add(entry);
            sumDurations += durations[i];
        }
        long total = sumDurations - (long) (n - 1) * fade;
        if (audioMs > total) {

            total = audioMs;
        }

        Dynamic dynamic = new Dynamic();
        dynamic.setUserId(ctx.getUserId());
        dynamic.setTitle(StringUtils.hasText(dto.getTitle()) ? dto.getTitle().trim() : "");
        dynamic.setCanvasType(ctx.getCanvasType());
        dynamic.setCanvasW(ctx.getCanvasW());
        dynamic.setCanvasH(ctx.getCanvasH());
        dynamic.setTransition(ctx.getTransition());
        dynamic.setFadeMs((int) fade);
        dynamic.setAudioId(ctx.getAudioId());
        dynamic.setAudioObjectKey(ctx.getAudioObjectKey());
        dynamic.setAudioTitle(ctx.getAudioTitle());
        dynamic.setAudioDurationMs((int) audioMs);
        dynamic.setTotalDurationMs((int) total);
        try {
            dynamic.setTimelineJson(objectMapper.writeValueAsString(timeline));
        } catch (Exception e) {

            log.warn("dynamic 序列化时间轴失败", e);
            throw new BizException(BizException.BIZ_ERROR, "发布失败");
        }
        dynamic.setIsDelete(0);
        dynamic.setCreateTime(java.time.LocalDateTime.now());
        dynamic.setDescription(StringUtils.hasText(dto.getDescription()) ? dto.getDescription().trim() : null);
        dynamic.setVisibility("public");
        dynamic.setAllowComment(dto.getAllowComment() != null && dto.getAllowComment() == 0 ? 0 : 1);
        // content 池先行: 预分配雪花id, 动态表主键与之相同
        Content content = new Content()
                .setId(IdWorker.getId())
                .setType(ContentTypeEnum.DYNAMIC)
                .setAuthorId(ctx.getUserId())
                .setStatus(VideoStatusEnum.PUBLISHED.getCode())
                .setVisibility("public")
                .setCreateTime(dynamic.getCreateTime());
        dynamic.setId(content.getId());
        contentMapper.insert(content);
        dynamicMapper.insert(dynamic);
        // ES 全量文档由打标链路单写: 延迟任务里 LLM 打标后整档同步(失败也发兜底文档保可搜)
        autoTagPublisher.sendAutoTagTask(content.getId(), ContentTypeEnum.DYNAMIC);
        return String.valueOf(dynamic.getId());
    }

    @Override
    public PageResult<DynamicVO> feed(PageQuery pageQuery) {
        Page<Dynamic> page = dynamicMapper.selectPage(pageQuery.toPage(),
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Dynamic>()
                        .eq(Dynamic::getIsDelete, 0)
                        .eq(Dynamic::getVisibility, "public")
                        .orderByDesc(Dynamic::getCreateTime));
        List<DynamicVO> vos = toVOs(page.getRecords());
        return PageResult.of(page.getTotal(), page.getPages(), vos);
    }

    @Override
    public DynamicVO detail(Long id) {
        Dynamic dynamic = dynamicMapper.selectById(id);
        if (dynamic == null || dynamic.getIsDelete() != null && dynamic.getIsDelete() == 1) {

            throw new BizException(BizException.BIZ_ERROR, "动态不存在");
        }
        // 私密动态仅作者本人可见
        if ("private".equals(dynamic.getVisibility())) {

            String uid = UserContext.getUserId();
            if (uid == null || !uid.equals(String.valueOf(dynamic.getUserId()))) {

                throw new BizException(BizException.BIZ_ERROR, "无权查看该动态");
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
        // 作者粉丝数(详情层展示, 与视频详情同口径)
        UserSummary author = userPort.getUser(dynamic.getUserId());
        if (author != null) {

            vo.setAuthorFanCount(author.getFans());
        }
        return vo;
    }

    @Override
    public DynamicDownloadVO requestDownload(Long dynamicId) {
        Dynamic dynamic = dynamicMapper.selectById(dynamicId);
        if (dynamic == null || dynamic.getIsDelete() != null && dynamic.getIsDelete() == 1) {

            throw new BizException(BizException.BIZ_ERROR, "动态不存在");
        }
        TranscodeSubmitDTO submit = buildSubmitDTO(dynamic);
        // 任务归属请求者: SSE 通知与状态查询权限都按这个 id
        submit.setUserId(getUserId());
        TranscodeTaskVO task;
        try {
            task = transcodePort.submitTask(submit);
        } catch (Exception e) {

            log.error("提交转码任务失败 dynamicId={}", dynamicId, e);
            throw new BizException(BizException.BIZ_ERROR, "转码服务暂不可用，请稍后再试");
        }
        if (task == null || task.getTaskId() == null) {

            throw new BizException(BizException.BIZ_ERROR, "创建下载任务失败");
        }
        return toDownloadVO(task);
    }

    @Override
    public DynamicDownloadVO getDownloadStatus(Long taskId) {
        Long userId = getUserId();
        TranscodeTaskVO task;
        try {
            task = transcodePort.getTask(taskId);
        } catch (Exception e) {

            log.error("查询转码任务失败 taskId={}", taskId, e);
            throw new BizException(BizException.BIZ_ERROR, "转码服务暂不可用，请稍后再试");
        }
        if (task == null || task.getTaskId() == null) {

            throw new BizException(BizException.BIZ_ERROR, "任务不存在");
        }
        if (!Objects.equals(task.getUserId(), userId)) {

            throw new BizException(BizException.BIZ_ERROR, "无权查看该任务");
        }
        return toDownloadVO(task);
    }

    @Override
    public void deleteDynamic(Long id) {
        Long userId = getUserId();
        // 事务只圈 DB 写(领域表 + content 池), ES 是慢 IO 留在提交后
        transactionTemplate.executeWithoutResult(tx -> {
            OwnershipUpdate.updateOrFail(dynamicMapper, Dynamic::getId, id, Dynamic::getUserId, userId,
                    w -> w.set(Dynamic::getIsDelete, 1), "删除失败,请稍后再试");
            // content 池无删除标记, 不同步摘除的话池分发与创作中心会把已删动态查回来
            contentService.removeById(id);
        });
        // ES 尽力删除, 失败不影响 DB 删除
        try {
            contentDocPort.deleteDoc(id);
        } catch (Exception e) {

            log.warn("ES 动态文档删除失败, dynamicId={}", id, e);
        }
        return;
    }

    @Override
    public void privateDynamic(Long id) {
        switchVisibility(id, "private");
    }

    @Override
    public void publicDynamic(Long id) {
        switchVisibility(id, "public");
    }

    private void switchVisibility(Long id, String visibility) {

        Long userId = getUserId();
        Dynamic dynamic = dynamicMapper.selectById(id);
        if (dynamic == null || !userId.equals(dynamic.getUserId())) {

            throw new BizException(BizException.BIZ_ERROR, "动态不存在");
        }
        OwnershipUpdate.updateOrFail(dynamicMapper, Dynamic::getId, id, Dynamic::getUserId, userId,
                w -> w.set(Dynamic::getVisibility, visibility), "修改失败");
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
        return;
    }

    @Override
    public void openComment(Long id) {
        switchComment(id, 1);
    }

    @Override
    public void closeComment(Long id) {
        switchComment(id, 0);
    }

    private void switchComment(Long id, int allow) {

        Long userId = getUserId();
        OwnershipUpdate.updateOrFail(dynamicMapper, Dynamic::getId, id, Dynamic::getUserId, userId,
                w -> w.set(Dynamic::getAllowComment, allow), "修改失败");
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
        List<UserSummary> authors = authorIds.isEmpty() ? List.of() : userPort.listByIds(authorIds);
        Map<Long, String> nicknames = UserSummaries.toNicknameMap(authors);
        Map<Long, String> avatars = UserSummaries.toAvatarMap(authors);

        List<DynamicVO> vos = new ArrayList<>(dynamics.size());
        for (Dynamic d : dynamics) {

            DynamicVO vo = new DynamicVO();
            vo.setId(String.valueOf(d.getId()));
            vo.setUserId(String.valueOf(d.getUserId()));
            vo.setAuthorId(String.valueOf(d.getUserId()));
            vo.setTitle(d.getTitle());
            vo.setTags(d.getTags());
            vo.setDescription(d.getDescription());
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

    private static Long getUserId() {

        String userId = UserContext.getUserId();
        if (userId == null) {

            throw new BizException(BizException.USER_ERROR, "用户未登录");
        }
        return Long.valueOf(userId);
    }
}
