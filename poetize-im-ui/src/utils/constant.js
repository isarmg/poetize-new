const isDevelopment = import.meta.env.DEV;
const origin = window.location.origin;

function envValue(value, fallback) {
  return typeof value === 'string' && value.trim() ? value.trim() : fallback;
}

function withoutTrailingSlash(value) {
  return value.replace(/\/+$/, '');
}

function withTrailingSlash(value) {
  return withoutTrailingSlash(value) + '/';
}

const baseURL = withoutTrailingSlash(envValue(
  import.meta.env.VITE_API_BASE_URL,
  isDevelopment ? 'http://localhost:8081' : origin + '/api'
));
const webBaseURL = withoutTrailingSlash(envValue(
  import.meta.env.VITE_WEB_BASE_URL,
  isDevelopment ? 'http://localhost' : origin
));
const imURL = withTrailingSlash(envValue(
  import.meta.env.VITE_IM_URL,
  isDevelopment ? 'http://localhost:81/im/' : origin + '/im/'
));

export default {
  baseURL,
  webBaseURL,
  imURL,
  imBaseURL: envValue(
    import.meta.env.VITE_WS_HOST,
    isDevelopment ? 'localhost' : window.location.host
  ),
  wsProtocol: envValue(
    import.meta.env.VITE_WS_PROTOCOL,
    window.location.protocol === 'https:' ? 'wss' : 'ws'
  ),
  wsPort: envValue(
    import.meta.env.VITE_WS_PORT,
    isDevelopment ? '9324' : ''
  ),

  hitokoto: "https://v1.hitokoto.cn",
  jinrishici: "https://v1.jinrishici.com/all.json",
  jitang: "https://api.oick.cn/dutang/api.php",
  shehui: "https://api.oick.cn/yulu/api.php",
  yiyan: "https://api.oick.cn/yiyan/api.php",
  dog: "https://api.oick.cn/dog/api.php",

  // 与主站、后端约定的协议常量；它会公开到客户端，不能作为服务端秘密。
  cryptojs_key: "sarasarasarasara",

  before_color_1: "black",
  after_color_1: "linear-gradient(45deg, #f43f3b, #ec008c)",

  before_color_2: "rgb(131, 123, 199)",
  after_color_2: "linear-gradient(45deg, #f43f3b, #ec008c)",

  tree_hole_color: ["rgb(180, 224, 255)", "rgb(180, 203, 255)", "rgb(246, 223, 255)", "rgb(255, 214, 198)", "rgb(255, 205, 143)", "rgb(238, 255, 143)", "rgb(220, 255, 165)", "rgb(164, 234, 192)", "rgb(202, 241, 233)", "rgb(230, 230, 250)"],

  emojiList: ['衰', '鄙视', '再见', '捂嘴', '奋斗', '白眼', '可怜', '皱眉', '鼓掌', '烦恼', '吐舌', '挖鼻', '委屈', '滑稽', '啊这', '生气', '害羞', '晕', '好色', '流泪', '吐血', '微笑', '酷', '坏笑', '吓', '大兵', '哭笑', '困', '呲牙']
}
