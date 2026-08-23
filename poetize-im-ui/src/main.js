import {createApp} from 'vue'
import App from './App.vue'
import router from './router'
import store from './store'
import {
  create,
  NAvatar,
  NInput,
  NDivider,
  NButton,
  NDrawer,
  NCard,
  NTabs,
  NTabPane,
  NSwitch,
  NModal,
  NBadge,
  NPopover,
  NImage,
  NPopconfirm
} from 'naive-ui'

import {
  ElUpload,
  ElButton,
  ElRadioGroup,
  ElRadioButton
} from 'element-plus'
import 'element-plus/dist/index.css'

import http, {getStoredUserToken} from './utils/request'
import common from './utils/common'
import constant from './utils/constant'

import 'vfonts/FiraCode.css'
import './assets/css/index.css'
import './assets/css/color.css'
import './assets/css/animation.css'

const naive = create({
  components: [NAvatar, NInput, NDivider, NButton,
    NDrawer, NCard, NTabs, NTabPane, NSwitch, NModal, NBadge,
    NPopover, NImage, NPopconfirm]
})

const app = createApp(App)
app.use(store)
app.use(naive)

app.component(ElUpload.name, ElUpload)
app.component(ElButton.name, ElButton)
app.component(ElRadioGroup.name, ElRadioGroup)
app.component(ElRadioButton.name, ElRadioButton)

app.config.globalProperties.$http = http
app.config.globalProperties.$common = common
app.config.globalProperties.$constant = constant

function getSingleQueryValue(value) {
  return Array.isArray(value) ? value[0] : value
}

function hasStoredToken() {
  return Boolean(getStoredUserToken())
}

function hasStoredUser() {
  return Number.isSafeInteger(store.state.currentUser.id) && store.state.currentUser.id > 0
}

function redirectTo(url) {
  window.location.replace(url)
  return false
}

let sysConfigPromise

function ensureSysConfig() {
  if (!sysConfigPromise) {
    sysConfigPromise = http.get(constant.baseURL + '/sysConfig/listSysConfig', {}, false, {timeout: 10000})
      .then(result => {
        if (result.data !== null && typeof result.data === 'object' && !Array.isArray(result.data)) {
          store.commit('loadSysConfig', result.data)
          const storedType = localStorage.getItem('defaultStoreType')
          const configuredType = result.data['store.type']
          if ((storedType !== 'local' && storedType !== 'qiniu') && (configuredType === 'local' || configuredType === 'qiniu')) {
            localStorage.setItem('defaultStoreType', configuredType)
          }
        }
      })
      .catch(() => {
        // 请求失败时保留本地缓存，认证导航仍可继续。
      })
  }
  return sysConfigPromise
}

// 提前请求公开配置；仅受保护页面会在进入前等待它完成。
ensureSysConfig()

router.beforeEach(async (to) => {
  if (!to.meta.requiresAuth) {
    return true
  }

  if (to.path === '/') {
    const defaultStoreType = getSingleQueryValue(to.query.defaultStoreType)
    if (defaultStoreType === 'local' || defaultStoreType === 'qiniu') {
      localStorage.setItem('defaultStoreType', defaultStoreType)
    }

    const userToken = getSingleQueryValue(to.query.userToken)
    if (typeof userToken === 'string' && userToken.length > 0) {
      try {
        // 使用表单编码而不是手工拼接，避免令牌中的 +、&、= 被破坏。
        const result = await http.post(constant.baseURL + '/user/token', {userToken}, false)
        if (!common.isEmpty(result.data) && result.data.accessToken) {
          store.commit('loadCurrentUser', result.data)
          localStorage.setItem('userToken', result.data.accessToken)
          return redirectTo(constant.imURL)
        }
      } catch {
        // 统一在下方清理无效会话。
      }
      store.commit('loadCurrentUser', {})
      localStorage.removeItem('userToken')
      return redirectTo(constant.webBaseURL)
    }
  }

  if (hasStoredToken() && hasStoredUser()) {
    await ensureSysConfig()
    return true
  }

  store.commit('loadCurrentUser', {})
  localStorage.removeItem('userToken')
  return redirectTo(constant.webBaseURL)
})

app.use(router)
app.mount('#app')
