<script setup>
import { onBeforeUnmount } from "vue";
import {
  playSrc,
  setAudioElement,
  onTimeUpdate,
  onAudioMetadata,
  onTrackEnded,
  onAudioPlay,
  onAudioPause,
} from "../stores/musicPlayerStore.js";

function bindAudio(el) {
  setAudioElement(el);
}

onBeforeUnmount(() => {
  setAudioElement(null);
});
</script>

<template>
  <audio
    :ref="bindAudio"
    :src="playSrc || undefined"
    preload="metadata"
    crossorigin="anonymous"
    @timeupdate="onTimeUpdate"
    @play="onAudioPlay"
    @pause="onAudioPause"
    @loadedmetadata="onAudioMetadata"
    @ended="onTrackEnded"
  />
</template>
