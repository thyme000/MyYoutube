package cn.diaojiahong.myyoutube.admin.controller;


import cn.diaojiahong.myyoutube.common.media.HlsPlaylist;
import cn.diaojiahong.myyoutube.common.media.ContentRoutes;
import cn.diaojiahong.myyoutube.admin.security.AdminAuthorization;
import cn.diaojiahong.myyoutube.security.PlaybackTokens;
import cn.diaojiahong.myyoutube.content.api.ContentAdminPort;
import cn.diaojiahong.myyoutube.content.api.ContentAdminPort.ReviewStream;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;


@RestController
@RequestMapping(ContentRoutes.REVIEW_STREAM_BASE)
@RequiredArgsConstructor
public class AdminContentStreamController {
    private final AdminAuthorization authorization;
    private final ContentAdminPort content;

    @ModelAttribute
    void disableCaching(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, CacheControl.noStore().getHeaderValue());
    }

    @GetMapping(ContentRoutes.MASTER_PATH)
    public ResponseEntity<String> master(@PathVariable Long contentId,
                                         @RequestHeader(PlaybackTokens.INTERNAL_HEADER) String token) {
        Long viewer = authorization.requireAdmin();
        return playlist(content.masterPlaylist(new ReviewStream(
                contentId, viewer, 0, token)));
    }

    @GetMapping(ContentRoutes.RENDITION_PATH)
    public ResponseEntity<String> rendition(@PathVariable Long contentId, @PathVariable int index,
                                            @RequestHeader(PlaybackTokens.INTERNAL_HEADER) String token) {
        Long viewer = authorization.requireAdmin();
        return playlist(content.playlist(new ReviewStream(
                contentId, viewer, index, token)));
    }

    private ResponseEntity<String> playlist(String text) {
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(HlsPlaylist.CONTENT_TYPE)).body(text);
    }
}
