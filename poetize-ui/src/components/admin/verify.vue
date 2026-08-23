<template>
  <div class="myCenter verify-container">
    <div class="verify-content">
      <div>
        <el-avatar :size="50" :src="$store.state.webInfo.avatar"></el-avatar>
      </div>
      <div>
        <el-input v-model="account">
          <template #prepend>账号</template>
        </el-input>
      </div>
      <div>
        <el-input v-model="password" type="password">
          <template #prepend>密码</template>
        </el-input>
      </div>
      <div>
        <proButton :info="'提交'"
                   @click="login()"
                   :before="$constant.before_color_2"
                   :after="$constant.after_color_2">
        </proButton>
      </div>
    </div>
  </div>
</template>

<script>
  import {defineAsyncComponent} from 'vue';
  import {saveToken} from '../../utils/auth';
  const proButton = defineAsyncComponent(() => import("../common/proButton.vue"));

  export default {
    components: {
      proButton
    },
    data() {
      const requestedRedirect = this.$route.query.redirect;
      const redirect = typeof requestedRedirect === "string" && requestedRedirect.startsWith("/") && !requestedRedirect.startsWith("//")
        ? requestedRedirect
        : "/welcome";
      return {
        redirect,
        account: "",
        password: ""
      }
    },
    computed: {},
    created() {

    },
    methods: {
      login() {
        if (this.$common.isEmpty(this.account) || this.$common.isEmpty(this.password)) {
          this.$message({
            message: "请输入账号或密码！",
            type: "error"
          });
          return;
        }

        let user = {
          account: this.account.trim(),
          password: this.$common.encrypt(this.password.trim()),
          isAdmin: true
        };

        this.$http.post(this.$constant.baseURL + "/user/login", user, true, false)
          .then((res) => {
            if (!this.$common.isEmpty(res.data)) {
              const token = saveToken("adminToken", res.data.accessToken);
              if (!token) {
                throw new Error("登录响应缺少有效令牌！");
              }
              this.$store.commit("loadCurrentAdmin", res.data);
              this.account = "";
              this.password = "";
              this.$router.push(this.redirect);
            }
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

  .verify-container {
    height: 100vh;
    background: var(--backgroundPicture) center center / cover repeat;
  }

  .verify-content {
    background: var(--maxWhiteMask);
    padding: 30px 40px 5px;
    position: relative;
  }

  .verify-content > div:first-child {
    position: absolute;
    left: 50%;
    transform: translate(-50%);
    top: -25px;
  }

  .verify-content > div:not(:first-child) {
    margin: 25px 0;
  }

  .verify-content > div:last-child > div {
    margin: 0 auto;
  }

</style>
