import {createStore} from 'vuex'
import {hasValidToken} from '../utils/auth'

const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value)

function readStoredJson(key, fallback, validate) {
  try {
    const rawValue = localStorage.getItem(key)
    if (rawValue === null) return fallback
    const value = JSON.parse(rawValue)
    return validate(value) ? value : fallback
  } catch {
    return fallback
  }
}

function writeStoredJson(key, value) {
  try {
    localStorage.setItem(key, JSON.stringify(value))
  } catch {
    // 存储被禁用或空间不足时仍保持当前会话可用。
  }
}

function parseArray(value) {
  if (Array.isArray(value)) return value
  if (typeof value !== 'string') return []
  try {
    const parsed = JSON.parse(value)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

function normalizeWebInfo(value) {
  const webInfo = isRecord(value) ? {...value} : {}
  return {
    webName: '',
    footer: '',
    backgroundImage: '',
    avatar: '',
    ...webInfo,
    webTitle: Array.isArray(webInfo.webTitle)
      ? webInfo.webTitle
      : String(webInfo.webTitle || '').split(''),
    notices: parseArray(webInfo.notices),
    randomCover: parseArray(webInfo.randomCover)
  }
}

function normalizeSortInfo(value) {
  return Array.isArray(value)
    ? value
      .filter(isRecord)
      .sort((first, second) => Number(first.priority || 0) - Number(second.priority || 0))
    : []
}

export default createStore({
  state: {
    toolbar: readStoredJson('toolbar', {visible: false, enter: true}, isRecord),
    sortInfo: normalizeSortInfo(readStoredJson('sortInfo', [], Array.isArray)),
    currentUser: hasValidToken('userToken') ? readStoredJson('currentUser', {}, isRecord) : {},
    currentAdmin: hasValidToken('adminToken') ? readStoredJson('currentAdmin', {}, isRecord) : {},
    sysConfig: readStoredJson('sysConfig', {}, isRecord),
    webInfo: normalizeWebInfo(readStoredJson('webInfo', {}, isRecord))
  },
  getters: {
    articleTotal: state => state.sortInfo.reduce((total, sort) => {
      const count = Number(sort?.countOfSort)
      return total + (Number.isFinite(count) ? count : 0)
    }, 0),
    navigationBar: state => state.sortInfo.filter(sort => sort?.sortType === 0)
  },
  mutations: {
    changeToolbarStatus(state, toolbarState) {
      state.toolbar = isRecord(toolbarState) ? toolbarState : {visible: false, enter: true}
      writeStoredJson('toolbar', state.toolbar)
    },
    loadSortInfo(state, sortInfo) {
      state.sortInfo = normalizeSortInfo(sortInfo)
      writeStoredJson('sortInfo', state.sortInfo)
    },
    loadCurrentUser(state, user) {
      state.currentUser = isRecord(user) ? user : {}
      writeStoredJson('currentUser', state.currentUser)
    },
    loadSysConfig(state, sysConfig) {
      state.sysConfig = isRecord(sysConfig) ? sysConfig : {}
      writeStoredJson('sysConfig', state.sysConfig)
    },
    loadCurrentAdmin(state, user) {
      state.currentAdmin = isRecord(user) ? user : {}
      writeStoredJson('currentAdmin', state.currentAdmin)
    },
    loadWebInfo(state, webInfo) {
      state.webInfo = normalizeWebInfo(webInfo)
      writeStoredJson('webInfo', state.webInfo)
    }
  },
  actions: {},
  modules: {},
  plugins: []
})
