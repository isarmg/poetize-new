<template>
  <div>
    <div>
      <el-image style="animation: header-effect 2s"
                class="background-image"
                lazy
                :src="pageCover"
                fit="cover">
        <template #error>
          <div class="image-slot background-image-error"></div>
        </template>
      </el-image>
      <!-- 输入框 -->
      <div class="message-in" style="text-align: center">
        <h2 class="message-title">树洞</h2>
        <div>
          <input class="message-input"
                 type="text"
                 style="outline: none;width: 70%"
                 placeholder="留下点什么啦~"
                 v-model="messageContent"
                 @click="show = true"
                 maxlength="60"/>
          <button v-show="show"
                  style="margin-left: 12px;cursor: pointer;width: 20%"
                  @click="submitMessage"
                  class="message-input">发射
          </button>
        </div>
      </div>
      <!-- 弹幕 -->
      <div class="barrage-container">
        <vue-danmaku ref="danmaku" v-model:danmus="barrageList" loop>
          <template #danmu="{danmu}">
            <div class="danmaku-item">
              <img v-if="danmu.avatar" :src="danmu.avatar" alt="" class="danmaku-avatar">
              <span>{{ danmu.msg }}</span>
            </div>
          </template>
        </vue-danmaku>
      </div>
    </div>
    <div class="comment-wrap">
      <div class="comment-content">
        <comment :source="$constant.source"
                 :type="'message'"
                 :userId="$store.state.webInfo.adminUserId || $constant.userId"></comment>
      </div>
      <myFooter></myFooter>
    </div>
  </div>
</template>

<script>
  import {defineAsyncComponent} from 'vue';
  import VueDanmaku from 'vue-danmaku';

  const comment = defineAsyncComponent(() => import("./comment/comment.vue"));
  const myFooter = defineAsyncComponent(() => import("./common/myFooter.vue"));

  export default {
    components: {
      comment,
      myFooter,
      VueDanmaku
    },
    data() {
      return {
        show: false,
        messageContent: "",
        // background: {"background": "url(" + this.$store.state.webInfo.backgroundImage + ") center center / cover no-repeat"},
        barrageList: []
      };
    },
    created() {
      this.getTreeHole();
    },
    computed: {
      pageCover() {
        const covers = this.$store.state.webInfo.randomCover;
        if (!Array.isArray(covers) || covers.length === 0) return "";
        return covers[Math.floor(Math.random() * covers.length)];
      }
    },
    methods: {
      getTreeHole() {
        this.$http.get(this.$constant.baseURL + "/webInfo/listTreeHole")
          .then((res) => {
            if (!this.$common.isEmpty(res.data)) {
              res.data.forEach(m => {
                this.barrageList.push({
                  id: m.id,
                  avatar: m.avatar,
                  msg: m.message,
                  time: Math.floor(Math.random() * 5 + 10)
                });
              });
              this.$nextTick(() => this.$refs.danmaku?.play());
            }
          })
          .catch((error) => {
            this.$message({
              message: error.message,
              type: "error"
            });
          });
      },
      submitMessage() {
        if (this.messageContent.trim() === "") {
          this.$message({
            message: "你还没写呢~",
            type: "warning"
          });
          return;
        }

        let treeHole = {
          message: this.messageContent.trim()
        };

        if (!this.$common.isEmpty(this.$store.state.currentUser) && !this.$common.isEmpty(this.$store.state.currentUser.avatar)) {
          treeHole.avatar = this.$store.state.currentUser.avatar;
        }


        this.$http.post(this.$constant.baseURL + "/webInfo/saveTreeHole", treeHole)
          .then((res) => {
            if (!this.$common.isEmpty(res.data)) {
              this.barrageList.push({
                id: res.data.id,
                avatar: res.data.avatar,
                msg: res.data.message,
                time: Math.floor(Math.random() * 5 + 10)
              });
            }
            this.messageContent = "";
            this.show = false;
          })
          .catch((error) => {
            this.$message({
              message: error.message,
              type: "error"
            });
          });

      }
    }
  }
</script>

<style scoped>

  .message-in {
    position: absolute;
    left: 50%;
    top: 40%;
    transform: translate(-50%, -50%);
    color: var(--white);
    animation: hideToShow 2.5s;
    width: 360px;
    z-index: 10;
  }

  .message-title {
    user-select: none;
    text-align: center;
  }

  .message-input {
    border-radius: 1.2rem;
    border: var(--white) 1px solid;
    color: var(--white);
    background: var(--transparent);
    padding: 10px 10px;
  }

  .message-input::-webkit-input-placeholder {
    color: var(--white);
  }

  .barrage-container {
    position: absolute;
    top: 50px;
    left: 0;
    right: 0;
    bottom: 0;
    height: calc(100% - 50px);
    width: 100%;
    user-select: none;
    overflow: hidden;
  }

  .danmaku-item {
    display: flex;
    align-items: center;
    gap: 8px;
    color: var(--white);
  }

  .danmaku-avatar {
    width: 30px;
    height: 30px;
    border-radius: 50%;
    object-fit: cover;
  }

  .comment-wrap {
    background: var(--background);
    position: absolute;
    top: 100vh;
    width: 100%;
  }

  .comment-content {
    max-width: 800px;
    margin: 0 auto;
    padding: 40px 20px;
  }
</style>
