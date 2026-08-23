import {createApp} from 'vue'
import App from './App.vue'
import router from './router'
import store from './store'
import ElementPlus from 'element-plus'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
import $ from 'jquery'
import http from './utils/request'
import common from './utils/common'
import constant from './utils/constant'
//引入js
import './utils/live2d'
import './utils/title'
//引入css
import './assets/css/animation.css'
import './assets/css/index.css'
import './assets/css/tocbot.css'
import './assets/css/color.css'
import './assets/css/markdown-highlight.css'
import './assets/css/font-awesome.min.css'
import 'element-plus/dist/index.css'

window.$ = $
window.jQuery = $

const app = createApp(App)

app.use(router)
app.use(store)
app.use(ElementPlus)

app.config.globalProperties.$http = http
app.config.globalProperties.$common = common
app.config.globalProperties.$constant = constant
app.config.globalProperties.$icons = ElementPlusIconsVue

app.mount('#app')
