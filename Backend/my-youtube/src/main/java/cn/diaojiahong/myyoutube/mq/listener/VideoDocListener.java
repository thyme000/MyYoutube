package cn.diaojiahong.myyoutube.mq.listener;

import cn.diaojiahong.myyoutube.mq.message.VideoDocMessage;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDoc;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.util.Date;
import java.util.List;

import static cn.diaojiahong.myyoutube.common.constants.RabbitMqConstants.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class VideoDocListener {

    private final VideoDocRepository videoDocRepository;


    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = UPDATE_VIDEO_DOCUMENT_QUEUE, durable = "true"),
            exchange = @Exchange(name = UPDATE_VIDEO_DOCUMENT_EXCHANGE, type = ExchangeTypes.TOPIC),
            key = UPDATE_VIDEO_DOCUMENT_ROUTING_KEY
    ))
    public void updateVideoDocument(VideoDocMessage message) {
        Video video = message.getVideo();
        List<Long> tagIds = message.getTagIds();

        VideoDoc doc = new VideoDoc();
        doc.setId(video.getId());
        doc.setTitle(video.getTitle());
        doc.setCategory(video.getCategory().getCode());
        doc.setVideoType(video.getVideoType());
        doc.setStatus(video.getStatus().getCode());
        doc.setDescription(video.getDescription());
        doc.setTagIds(tagIds);
        doc.setAuthorId(video.getAuthorId());
        doc.setPlayCount(video.getPlayCount() != null ? video.getPlayCount() : 0L);
        doc.setLikeCount(video.getLikeCount() != null ? video.getLikeCount() : 0L);
        doc.setCreateTime(video.getCreateTime());
        videoDocRepository.save(doc);
    }
}
