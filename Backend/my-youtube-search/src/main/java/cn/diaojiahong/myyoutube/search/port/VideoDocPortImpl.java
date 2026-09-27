package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDocRepository;
import cn.diaojiahong.myyoutube.recommend.domain.doc.VideoDoc;
import cn.diaojiahong.myyoutube.search.api.VideoDocPort;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
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
    public void syncVisibility(Long videoId, VisibilitySync sync) {
        videoDocRepository.findById(videoId).ifPresent(doc -> {
            if ("private".equals(sync.getVisibility())) {
                doc.setSuggest(new Completion());
            } else if (sync.isRebuildSuggest()) {
                List<String> list = new ArrayList<>(List.of(
                        sync.getTitle(),
                        sync.getDescription(),
                        doc.getAuthorName()
                ));
                if (sync.getTags() != null) {
                    list.addAll(Arrays.asList(sync.getTags().split(",")));
                }
                doc.setSuggest(new Completion(list));
            }
            doc.setVisibility(sync.getVisibility());
            videoDocRepository.save(doc);
        });
    }
}
