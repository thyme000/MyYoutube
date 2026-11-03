package cn.diaojiahong.myyoutube.content.service;

import cn.diaojiahong.myyoutube.common.media.ContentMedia;
import cn.diaojiahong.myyoutube.common.media.DynamicMedia;
import cn.diaojiahong.myyoutube.common.media.ContentArtifacts.Chapter;
import cn.diaojiahong.myyoutube.common.result.ErrorCode;
import cn.diaojiahong.myyoutube.common.monitoring.ObservedBusiness;
import cn.diaojiahong.myyoutube.common.monitoring.BusinessOperation;
import cn.diaojiahong.myyoutube.common.exception.BizException;
import cn.diaojiahong.myyoutube.common.utils.S3StorageUtil;
import cn.diaojiahong.myyoutube.common.utils.JsonUtil;
import cn.diaojiahong.myyoutube.content.domain.po.Content;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentDetailVO;
import cn.diaojiahong.myyoutube.content.domain.vo.ContentFeedVO;
import cn.diaojiahong.myyoutube.content.service.impl.ContentFeedServiceImpl;
import cn.diaojiahong.myyoutube.content.domain.media.ContentMediaResource;
import cn.diaojiahong.myyoutube.security.PlaybackTokens.Scope;
import cn.diaojiahong.myyoutube.video.domain.po.ContentUnderstanding;
import cn.diaojiahong.myyoutube.video.service.ContentUnderstandingService;
import cn.hutool.core.bean.BeanUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import java.util.Arrays;
import cn.diaojiahong.myyoutube.video.api.dto.ContentMediaVO;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;

@Service
@RequiredArgsConstructor
public class ContentReadService {
    private final ContentAccessService access;
    private final ContentMediaResources resources;
    private final ContentFeedServiceImpl cards;
    private final ContentUnderstandingService understanding;
    private final S3StorageUtil storage;
    private final ContentStreamService streams;

    @ObservedBusiness(BusinessOperation.CONTENT_DETAIL)
    public ContentDetailVO detail(Long id, Long viewer) {
        Content content = access.readable(id, viewer);
        ContentMediaResource resource = resources.media(content);
        ContentMedia media = resource.media();
        List<ContentFeedVO> visible = cards.assembleCardsWithMedia(viewer, List.of(content), Set.of(), Map.of(id, media));
        if (visible.isEmpty()) throw new BizException(ErrorCode.NOT_FOUND, "内容不存在或不可访问");
        ContentDetailVO detail = BeanUtil.copyProperties(visible.get(0), ContentDetailVO.class);
        detail.setMedia(media.video() != null
                ? new ContentMediaVO("video", new ContentMediaVO.Video(media.video().durationMs(),
                        streams.playback(content, resource, viewer, Scope.VIEW)), null)
                : signedMedia(media));
        ContentUnderstanding row = understanding.get(id);
        if (row != null) {
            detail.setSummary(row.getSummary());
            detail.setChapters(row.getChaptersJson() == null ? List.of() : Arrays.asList(
                    JsonUtil.fromJson(
                            row.getChaptersJson(),
                            Chapter[].class)));
            detail.setSubtitleUrl(signed(row.getSubtitleKey()));
        }
        return detail;
    }

    @ObservedBusiness(BusinessOperation.SUBTITLES)
    public String subtitles(Long id, Long viewer) {
        access.readable(id, viewer);
        ContentUnderstanding row = understanding.get(id);
        if (row == null || !StringUtils.hasText(row.getSubtitleKey())) throw new BizException(ErrorCode.NOT_FOUND, "暂无字幕");
        return resources.subtitle(row.getSubtitleKey());
    }

    public ContentMediaVO adminMedia(Long id, Long viewer) {
        Content content = access.forReview(id);
        ContentMediaResource resource = resources.media(content);
        ContentMedia media = resource.media();
        return media.video() != null
                ? new ContentMediaVO("video", new ContentMediaVO.Video(media.video().durationMs(),
                        streams.playback(content, resource, viewer, Scope.REVIEW)), null)
                : signedMedia(media);
    }

    private ContentMediaVO signedMedia(ContentMedia media) {
        DynamicMedia dynamic = media.dynamic();
        List<ContentMediaVO.Image> images = IntStream.range(0, dynamic.items().size()).mapToObj(i -> {
            DynamicMedia.Image image = dynamic.items().get(i);
            return new ContentMediaVO.Image(i, signed(image.imageObjectKey()), image.startMs(),
                    image.durationMs(), image.focusX(), image.focusY());
        }).toList();
        DynamicMedia.Audio audio = dynamic.audio();
        return new ContentMediaVO("dynamic", null,
                new ContentMediaVO.Dynamic(dynamic.canvasType(), dynamic.canvasW(), dynamic.canvasH(),
                        dynamic.transition(), dynamic.fadeMs(), images, audio == null ? null :
                        new ContentMediaVO.Audio(audio.id()
                                .toString(),
                                signed(audio.objectKey()),
                                audio.title(),
                                audio.durationMs()), dynamic.exportDurationMs()));
    }

    private String signed(String key) {
        return StringUtils.hasText(key) ? storage.signGetUrl(key, 1200) : null;
    }
}
