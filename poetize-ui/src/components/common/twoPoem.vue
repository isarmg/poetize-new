<template>
  <div class="poem-container myCenter my-animation-hideToShow"
       v-if="!$common.isEmpty(guShi.origin) || !$common.isEmpty(hitokoto.hitokoto)">
    <!-- 背景图片 -->
    <el-image class="my-el-image poem-image"
              style="position: absolute;margin-top: -50px"
              lazy
              :src="poemCover"
              fit="cover">
      <template #error><div class="image-slot"></div></template>
    </el-image>
    <div class="poem-wrap">
      <div v-if="isShehui"><span>鬼畜全明星</span></div>
      <div v-else><span>{{isHitokoto?hitokoto.from:guShi.origin}}</span></div>
      <p class="poem">{{isHitokoto?hitokoto.hitokoto:guShi.content}}</p>
      <p class="info" v-if="!isShehui && (!isHitokoto || (isHitokoto && !$common.isEmpty(hitokoto.from_who)))">
        {{isHitokoto?hitokoto.from_who:guShi.author}}
      </p>
    </div>
  </div>
</template>
<script>
  export default {
    props: {
      isHitokoto: {
        type: Boolean,
        default: true
      },
      isShehui: {
        type: Boolean,
        default: false
      }
    },
    data() {
      return {
        guShi: {
          "content": "...",
          "origin": "...",
          "author": "...",
          "category": "..."
        },
        hitokoto: {
          "hitokoto": "...",
          "from": "...",
          "from_who": "..."
        },
        request: null
      };
    },
    computed: {
      poemCover() {
        const covers = this.$store.state.webInfo.randomCover;
        if (!Array.isArray(covers) || covers.length === 0) return "";
        return covers[Math.floor(Math.random() * covers.length)];
      }
    },
    created() {
      if (!this.isShehui) {
        if (this.isHitokoto) {
          this.getHitokoto();
        } else {
          this.getGuShi();
        }
      } else {
        this.hitokoto.from = "";
        this.hitokoto.from_who = "";
        this.sendShehui();
      }
    },

    beforeUnmount() {
      this.request?.abort();
      this.request = null;
    },

    methods: {
      sendShehui() {
        let that = this;
        let xhr = new XMLHttpRequest();
        this.request = xhr;
        xhr.open('get', this.$constant.shehui);
        xhr.onreadystatechange = function () {
          if (xhr.readyState === 4 && xhr.status >= 200 && xhr.status < 300) {
            const parsed = that.$common.parseJson(xhr.responseText, xhr.responseText);
            if (typeof parsed === "string") {
              that.hitokoto.hitokoto = parsed;
            }
          }
        };
        xhr.send();
      },
      getGuShi() {
        let that = this;
        let xhr = new XMLHttpRequest();
        this.request = xhr;
        xhr.open('get', this.$constant.jinrishici);
        xhr.onreadystatechange = function () {
          if (xhr.readyState === 4 && xhr.status >= 200 && xhr.status < 300) {
            const result = that.$common.parseJson(xhr.responseText, null);
            if (result && typeof result === "object") {
              that.guShi = result;
            }
          }
        };
        xhr.send();
      },
      getHitokoto() {
        let that = this;
        let xhr = new XMLHttpRequest();
        this.request = xhr;
        xhr.open('get', this.$constant.hitokoto);
        xhr.onreadystatechange = function () {
          if (xhr.readyState === 4 && xhr.status >= 200 && xhr.status < 300) {
            const result = that.$common.parseJson(xhr.responseText, null);
            if (result && typeof result === "object") {
              that.hitokoto = result;
            }
          }
        }
        xhr.send();
      }
    }
  };
</script>
<style scoped>

  .poem-container {
    padding: 90px 0 40px;
    position: relative;
  }

  .poem-wrap {
    border-radius: 10px;
    z-index: 10;
    text-align: center;
    letter-spacing: 4px;
    font-weight: 300;
    width: 100%;
    max-width: 800px;
  }

  .poem-wrap div span {
    padding: 5px 10px;
    color: var(--white);
    font-size: 2em;
    border-radius: 5px;
  }

  .poem-wrap p {
    width: 100%;
    max-width: 800px;
    color: var(--white);
  }

  .poem-wrap p.poem {
    margin: 40px auto;
    font-size: 1.5em;
  }

  .poem-wrap p.info {
    margin: 20px auto 40px;
    font-size: 1.1em;
  }
</style>
