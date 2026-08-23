<template>
  <div>
    <el-upload
      class="upload-demo"
      :action="$store.state.sysConfig.qiniuUrl"
      multiple
      drag
      :limit="maxNumber"
      ref="upload"
      :auto-upload="false"
      :http-request="customUpload"
      :on-change="handleChange"
      :on-success="handleSuccess"
      :on-error="handleError"
      :on-exceed="handleExceed"
      list-type="picture"
      accept=".jpg,.jpeg,.png,.gif,.webp,.avif,image/jpeg,image/png,image/gif,image/webp,image/avif">
      <div class="el-upload__text">
        <svg viewBox="0 0 1024 1024" width="40" height="40">
          <path
            d="M666.2656 629.4528l-113.7664-112.4864c-20.7872-20.5824-54.3232-20.5312-75.1104 0.1024l-113.3056 112.4864c-20.8896 20.736-21.0432 54.528-0.256 75.4688 20.736 20.8896 54.528 21.0432 75.4688 0.256l22.6304-22.4256v189.5936c0 29.44 23.9104 53.3504 53.3504 53.3504s53.3504-23.9104 53.3504-53.3504v-189.5424l22.6816 22.4256a53.1456 53.1456 0 0 0 37.5296 15.4112c13.7728 0 27.4944-5.2736 37.9392-15.8208 20.6336-20.9408 20.4288-54.7328-0.512-75.4688z"
            fill="#FFE37B"></path>
          <path
            d="M820.992 469.504h-5.376c-3.072-163.328-136.3456-294.8096-300.4416-294.8096S217.856 306.1248 214.784 469.504H209.408c-100.7104 0-182.3744 81.664-182.3744 182.3744s81.664 182.3744 182.3744 182.3744h209.7664V761.856c-30.208 5.5808-62.464-3.3792-85.6576-26.7264-37.3248-37.5808-37.0688-98.5088 0.512-135.7824l113.3056-112.4864c37.2224-36.9664 97.8432-37.0176 135.168-0.1536l113.7664 112.4864c18.2272 18.0224 28.3648 42.0864 28.5184 67.7376 0.1536 25.6512-9.728 49.8176-27.7504 68.0448a95.40096 95.40096 0 0 1-68.3008 28.5184c-5.9392 0-11.776-0.512-17.5104-1.5872v72.3456h209.7664c100.7104 0 182.3744-81.664 182.3744-182.3744S921.7024 469.504 820.992 469.504z"
            fill="#8C7BFD"></path>
        </svg>
        <div>拖拽上传 / 点击上传</div>
      </div>
      <template #tip>
        <div class="el-upload__tip">
          一次最多上传{{maxNumber}}张图片，且每张图片不超过{{maxSize}}M！
        </div>
      </template>
    </el-upload>

    <div style="text-align: center;margin-top: 20px">
      <el-button type="success" style="font-size: 12px" @click="submitUpload">
        上传
      </el-button>
    </div>
  </div>
</template>

<script>
  import {ElMessage} from "element-plus";
  import upload from '../../utils/ajaxUpload';

  const ALLOWED_IMAGE_TYPES = {
    'image/jpeg': ['jpg', 'jpeg'],
    'image/png': ['png'],
    'image/gif': ['gif'],
    'image/webp': ['webp'],
    'image/avif': ['avif']
  };

  function isAllowedImage(file) {
    if (!file || typeof file.name !== 'string') {
      return false;
    }
    const extension = file.name.includes('.') ? file.name.split('.').pop().toLowerCase() : '';
    return Array.isArray(ALLOWED_IMAGE_TYPES[file.type]) && ALLOWED_IMAGE_TYPES[file.type].includes(extension);
  }

  export default {
    props: {
      prefix: {
        type: String,
        default: ""
      },
      maxSize: {
        type: Number,
        default: 5
      },
      maxNumber: {
        type: Number,
        default: 5
      }
    },

    data() {
      const storedType = localStorage.getItem("defaultStoreType");
      const configuredType = this.$store.state.sysConfig['store.type'];
      return {
        storeType: storedType === 'local' || storedType === 'qiniu' ? storedType : configuredType
      }
    },

    methods: {
      submitUpload() {
        if (this.storeType !== "local" && this.storeType !== "qiniu") {
          ElMessage({
            message: "未配置有效的图片存储方式！",
            type: 'error'
          });
          return;
        }
        this.$refs.upload.submit();
      },

      // 文件上传成功时的钩子
      handleSuccess(response, file) {
        let url;
        if (this.storeType === "local") {
          url = response?.data;
        } else if (this.storeType === "qiniu") {
          const downloadUrl = this.$store.state.sysConfig['qiniu.downloadUrl'];
          if (typeof downloadUrl === 'string' && response?.key) {
            url = downloadUrl + response.key;
            this.$common.saveResource(this, this.prefix, url, file.size, file.raw?.type || '', file.name, "qiniu");
          }
        }
        if (!url) {
          ElMessage({
            message: "上传响应中缺少图片地址！",
            type: 'error'
          });
          return;
        }
        this.$emit("addPicture", url);
      },

      customUpload(options) {
        if (!isAllowedImage(options.file)) {
          return Promise.reject(new Error("只允许上传 JPG、PNG、GIF、WebP 或 AVIF 位图！"));
        }
        let suffix = "";
        if (options.file.name.lastIndexOf('.') !== -1) {
          suffix = options.file.name.substring(options.file.name.lastIndexOf('.'));
        }

        const username = String(this.$store.state.currentUser.username || 'user').replace(/[^a-zA-Z]/g, '');
        let key = this.prefix + "/" + username + this.$store.state.currentUser.id + new Date().getTime() + Math.floor(Math.random() * 1000) + suffix;

        let data = {};
        data.key = key;
        options.data = data;

        if (this.storeType === "local") {
          data.relativePath = key;
          data.type = this.prefix;
          data.storeType = this.storeType;
          data.originalName = options.file.name;
          data.file = options.file;

          return this.$http.upload(this.$constant.baseURL + "/resource/upload", data, options);
        } else if (this.storeType === "qiniu") {
          const action = this.$store.state.sysConfig.qiniuUrl;
          if (typeof action !== 'string' || !action.trim()) {
            return Promise.reject(new Error("未配置七牛云上传地址！"));
          }

          return this.$http.get(this.$constant.baseURL + "/qiniu/getUpToken", {key})
            .then((res) => {
              if (!res || !res.data) {
                throw new Error("服务未返回上传凭证！");
              }
              data.token = res.data;
              // ajaxUpload 通过回调完成；包装成 Promise，避免异步函数提前把 XHR 当成成功响应。
              return new Promise((resolve, reject) => {
                upload({
                  ...options,
                  action: action.trim(),
                  onSuccess: resolve,
                  onError: reject
                });
              });
            });
        }

        return Promise.reject(new Error("未配置有效的图片存储方式！"));
      },

      handleError(err) {
        ElMessage({
          message: err?.message || String(err),
          type: 'error'
        });
      },

      handleExceed() {
        ElMessage({
          message: "一次最多上传" + this.maxNumber + "张图片！",
          type: 'warning'
        });
      },

      // 添加文件、上传成功和上传失败时都会被调用
      handleChange(file, fileList) {
        let flag = false;

        if (!isAllowedImage(file.raw)) {
          ElMessage({
            message: "只允许上传 JPG、PNG、GIF、WebP 或 AVIF 位图！",
            type: 'warning'
          });
          flag = true;
        } else if (file.size > this.maxSize * 1024 * 1024) {
          ElMessage({
            message: "图片最大为" + this.maxSize + "M！",
            type: 'warning'
          });
          flag = true;
        }

        if (fileList.length > this.maxNumber) {
          flag = true;
        }

        if (flag) {
          this.$refs.upload?.handleRemove(file);
        }
      }
    },
    emits: ['addPicture']
  }
</script>

<style scoped>

</style>
