package cn.diaojiahong.myyoutube.content.controller;


import cn.diaojiahong.myyoutube.common.media.HlsPlaylist;
import cn.diaojiahong.myyoutube.common.media.ContentRoutes;
import cn.diaojiahong.myyoutube.common.utils.UserContext;
import cn.diaojiahong.myyoutube.content.service.ContentStreamService;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.ModelAttribute;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(ContentRoutes.VIEW_STREAM_BASE)
@RequiredArgsConstructor
public class ContentStreamController {
    private final ContentStreamService streams;

    @ModelAttribute
    void disableCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    @GetMapping(ContentRoutes.RENDITION_PATH)
    public ResponseEntity<String> playlist(@PathVariable Long contentId, @PathVariable int index,
                                           @RequestHeader(PlaybackTokens.INTERNAL_HEADER) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(HlsPlaylist.CONTENT_TYPE))
                .body(streams.playlist(contentId, index, UserContext.requireUserId(), token));
    }

    @GetMapping(ContentRoutes.MASTER_PATH)
    public ResponseEntity<String> masterPlaylist(@PathVariable Long contentId,
                                                 @RequestHeader(PlaybackTokens.INTERNAL_HEADER) String token) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(HlsPlaylist.CONTENT_TYPE))
                .body(streams.masterPlaylist(contentId, UserContext.requireUserId(), token));
    }

}
