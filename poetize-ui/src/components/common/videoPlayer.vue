<template>
  <div class="video-player-box">
    <video ref="videoElement" class="video-js vjs-big-play-centered" controls playsinline></video>
  </div>
</template>

<script>
  import 'video.js/dist/video-js.css'
  import {markRaw} from 'vue'
  import videojs from 'video.js'

  export default {
    props: {
      url: {
        type: Object,
        default: () => ({src: '', type: ''})
      },
      cover: {
        type: String,
        default: ""
      }
    },
    data() {
      return {
        player: null
      }
    },
    watch: {
      url: {
        handler() {
          this.updateSource()
        },
        deep: true
      },
      cover() {
        this.player?.poster(this.cover)
      }
    },
    mounted() {
      this.player = markRaw(videojs(this.$refs.videoElement, {
        preload: 'metadata',
        fluid: true,
        loop: false,
        muted: false,
        language: 'zh-CN',
        autoplay: false,
        playbackRates: [0.5, 1, 1.5, 2],
        poster: this.cover,
        sources: this.url?.src ? [this.url] : [],
        notSupportedMessage: '此视频暂无法播放'
      }))
    },
    beforeUnmount() {
      this.player?.dispose()
      this.player = null
    },
    methods: {
      updateSource() {
        if (this.player && this.url?.src) {
          this.player.src(this.url)
          this.player.poster(this.cover)
        }
      }
    }
  }
</script>

<style>

  .video-player-box {
    border-radius: 5px;
    overflow: hidden;
  }

  .vjs-big-play-button {
    left: 50% !important;
    top: 50% !important;
    transform: translate(-50%, -50%) !important;
  }

</style>
