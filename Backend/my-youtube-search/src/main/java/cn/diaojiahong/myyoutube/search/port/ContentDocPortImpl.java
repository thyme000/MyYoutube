package cn.diaojiahong.myyoutube.search.port;

import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDocRepository;
import cn.diaojiahong.myyoutube.recommend.domain.doc.ContentDoc;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort;
import cn.diaojiahong.myyoutube.search.api.ContentDocPort.StatusSync;
import cn.diaojiahong.myyoutube.search.api.VisibilitySync;
import lombok.RequiredArgsConstructor;
import org.springframework.data.elasticsearch.core.suggest.Completion;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Component
@RequiredArgsConstructor
public class ContentDocPortImpl implements ContentDocPort {

    private final ContentDocRepository contentDocRepository;

    @Override
    public void deleteDoc(Long contentId) {
        contentDocRepository.deleteById(contentId);
    }

    @Override
    public void adjustPlayCount(Long contentId, Long delta) {
        contentDocRepository.findById(contentId).ifPresent(doc -> {
            doc.setPlayCount((doc.getPlayCount() != null ? doc.getPlayCount() : 0L) + delta);
            contentDocRepository.save(doc);
        });
    }

    @Override
    public void syncPlayLike(Long contentId, Long playCount, Long likeCount) {
        contentDocRepository.findById(contentId).ifPresent(doc -> {
            doc.setPlayCount(playCount);
            doc.setLikeCount(likeCount);
            contentDocRepository.save(doc);
        });
    }

    @Override
    public void syncStatus(Long contentId, StatusSync sync) {
        contentDocRepository.findById(contentId).ifPresent(doc -> {
            if (sync.rebuildSuggest()) {
                // List.of 不接受 null 元素, 无简介/未取到作者名的视频要跳过
                List<String> list = new ArrayList<>();
                if (sync.title() != null) {
                    list.add(sync.title());
                }
                if (sync.description() != null) {
                    list.add(sync.description());
                }
                if (doc.getAuthorName() != null) {
                    list.add(doc.getAuthorName());
                }
                if (sync.tags() != null) {
                    list.addAll(Arrays.asList(sync.tags().split(",")));
                }
                doc.setSuggest(new Completion(list));
            } else {
                doc.setSuggest(new Completion());
            }
            doc.setStatus(sync.statusCode());
            contentDocRepository.save(doc);
        });
    }

    @Override
    public void syncVisibility(Long contentId, VisibilitySync sync) {
        contentDocRepository.findById(contentId).ifPresent(doc -> {
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
            contentDocRepository.save(doc);
        });
    }
}
