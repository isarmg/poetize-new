<template>
  <div>
    <transition name="body">
      <div v-show="showEmoji" class="emoji-body">
        <span class="emoji-item"
              v-for="(value, key) in emojiListURL"
              :key="key"
              @click="addEmoji(key)">
          <img loading="lazy" class="emoji" :src="value" :title="key" width="30" height="30"/>
        </span>
      </div>
    </transition>
  </div>
</template>

<script>
  export default {
    emits: ['addEmoji'],
    props: {
      showEmoji: {
        type: Boolean
      }
    },
    data() {
      return {
        emojiList: this.$constant.emojiList,
        emojiListURL: {}
      };
    },
    created() {
      this.emojiListURL = this.getEmojiList(this.emojiList);
    },
    methods: {
      addEmoji(key) {
        this.$emit("addEmoji", key);
      },
      getEmojiList(emojiList) {
        const prefix = this.$store.state.sysConfig['webStaticResourcePrefix'];
        if (typeof prefix !== 'string' || !prefix) {
          return {};
        }
        let emojiName;
        let url;
        let result = {}
        for (let i = 0; i < emojiList.length; i++) {
          emojiName = "[" + emojiList[i] + "]";
          let j = i + 1;
          url = prefix + "emoji/q" + j + ".gif";
          result[emojiName] = url;
        }
        return result;
      }
    }
  }
</script>

<style scoped>

  .emoji-body {
    max-width: 400px;
  }

  .emoji-item {
    cursor: pointer;
    display: inline-block;
  }

  .emoji-item:hover {
    transition: all 0.2s;
    border-radius: 0.25rem;
    background: var(--lightGray);
  }

  .emoji {
    margin: 0.25rem;
    /* 把此元素放置在父元素的中部 */
    vertical-align: middle;
  }

  .body-enter-active, .body-leave-active {
    transition: all 0.3s;
  }

  .body-enter-from, .body-leave-to {
    opacity: 0;
    transform: scale(0.5);
  }
</style>
