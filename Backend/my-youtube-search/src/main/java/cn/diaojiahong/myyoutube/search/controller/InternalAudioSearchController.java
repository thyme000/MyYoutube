package cn.diaojiahong.myyoutube.search.controller;

import cn.diaojiahong.myyoutube.common.media.AudioDiscoveryContracts.*;
import cn.diaojiahong.myyoutube.search.api.AudioSearchPort;
import cn.diaojiahong.myyoutube.search.service.AudioSearchService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/audio-search")
@RequiredArgsConstructor
public class InternalAudioSearchController implements AudioSearchPort {
    private final AudioSearchService service;
    @Override public Slice search(Query query) { return service.search(query); }
    @Override public void rebuild(Long id) { service.rebuild(id); }
}
