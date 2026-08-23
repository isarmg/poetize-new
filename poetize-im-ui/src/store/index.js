import {createStore} from 'vuex'

function readStoredObject(key) {
  const value = localStorage.getItem(key)
  if (!value) {
    return {}
  }

  try {
    const parsed = JSON.parse(value)
    return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : {}
  } catch {
    return {}
  }
}

export default createStore({
  state: {
    currentUser: readStoredObject('currentUser'),
    sysConfig: readStoredObject('sysConfig')
  },
  getters: {},
  mutations: {
    loadCurrentUser(state, user) {
      state.currentUser = user !== null && typeof user === 'object' && !Array.isArray(user) ? user : {};
      localStorage.setItem("currentUser", JSON.stringify(state.currentUser));
    },
    loadSysConfig(state, sysConfig) {
      state.sysConfig = sysConfig !== null && typeof sysConfig === 'object' && !Array.isArray(sysConfig)
        ? sysConfig
        : {};
      localStorage.setItem('sysConfig', JSON.stringify(state.sysConfig));
    }
  },
  actions: {},
  modules: {},
  plugins: []
})
