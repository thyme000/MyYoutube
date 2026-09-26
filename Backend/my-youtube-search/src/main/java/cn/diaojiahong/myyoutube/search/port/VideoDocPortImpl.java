package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDoc;
import cn.diaojiahong.myyoutube.video.domain.po.Video;
import cn.diaojiahong.myyoutube.video.port.VideoDocPort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class VideoDocPortImpl implements VideoDocPort {

    private final VideoDocRepository videoDocRepository;

    @Override
    public void deleteDoc(Long videoId) {
        videoDocRepository.deleteById(videoId);
    }

    @Override
    public void syncVisibility(Long videoId, Video video, String visibility, boolean rebuildSuggest) {
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            if ("private".equals(visibility)) {
                doc.setSuggest(new Completion());
            } else if (rebuildSuggest) {
                List<String> list = new ArrayList<>(List.of(
                        video.getTitle(),
                        video.getDescription(),
                        doc.getAuthorName()
                ));
                if (video.getTags() != null) {
                    list.addAll(Arrays.asList(video.getTags().split(",")));
                }
                doc.setSuggest(new Completion(list));
            }
            doc.setVisibility(visibility);
            videoDocRepository.save(doc);
        });
    }
}
