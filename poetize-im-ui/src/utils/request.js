import axios from "axios";
import constant from "./constant";
//处理url参数
import qs from "qs";

import store from "../store";

export function getStoredUserToken() {
  const value = localStorage.getItem("userToken");
  if (typeof value !== 'string') {
    return '';
  }
  const token = value.trim();
  return token && !/^(null|undefined)$/i.test(token) ? token : '';
}

function getAuthHeaders() {
  const token = getStoredUserToken();
  return token ? {Authorization: token} : {};
}

axios.defaults.baseURL = constant.baseURL;

function toFormData(param = {}) {
  if (param instanceof FormData) {
    return param;
  }

  const formData = new FormData();
  Object.entries(param).forEach(([key, value]) => {
    if (value === null || typeof value === "undefined") {
      return;
    }

    if (Array.isArray(value)) {
      value.forEach(item => formData.append(key, item));
    } else {
      formData.append(key, value);
    }
  });
  return formData;
}


// 添加请求拦截器
axios.interceptors.request.use(function (config) {
  // 在发送请求之前做些什么
  return config;
}, function (error) {
  // 对请求错误做些什么
  return Promise.reject(error);
});


// 添加响应拦截器
axios.interceptors.response.use(function (response) {
  const data = response.data;
  if (data !== null && typeof data === 'object' && Object.prototype.hasOwnProperty.call(data, "code") && data.code !== 200) {
    if (data.code === 300) {
      store.commit("loadCurrentUser", {});
      localStorage.removeItem("userToken");
      window.location.replace(constant.webBaseURL + "/user");
    }
    return Promise.reject(new Error(data.message || '请求失败'));
  } else {
    return response;
  }
}, function (error) {
  // 对响应错误做点什么
  return Promise.reject(error);
});


// 当data为URLSearchParams对象时设置为application/x-www-form-urlencoded;charset=utf-8
// 当data为普通对象时，会被设置为application/json;charset=utf-8


export default {
  post(url, params = {}, json = true) {
    let config = {
      headers: getAuthHeaders()
    };
    if (!json) {
      config.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
    }

    return new Promise((resolve, reject) => {
      axios
        .post(url, json ? params : qs.stringify(params), config)
        .then(res => {
          resolve(res.data);
        })
        .catch(err => {
          reject(err);
        });
    });
  },

  get(url, params = {}, authenticated = true, options = {}) {
    const headers = authenticated
      ? getAuthHeaders()
      : {};

    return new Promise((resolve, reject) => {
      axios.get(url, {
        ...options,
        params: params,
        headers: {...options.headers, ...headers}
      }).then(res => {
        resolve(res.data);
      }).catch(err => {
        reject(err)
      })
    });
  },

  upload(url, param, option) {
    let config = {
      headers: getAuthHeaders(),
      timeout: 60000
    };
    if (typeof option !== "undefined") {
      config.onUploadProgress = progressEvent => {
        if (progressEvent.total > 0) {
          progressEvent.percent = progressEvent.loaded / progressEvent.total * 100;
        }
        option.onProgress(progressEvent);
      };
    }

    return new Promise((resolve, reject) => {
      axios
        .post(url, toFormData(param), config)
        .then(res => {
          resolve(res.data);
        })
        .catch(err => {
          reject(err);
        });
    });
  },

  uploadQiniu(url, param) {
    let config = {
      timeout: 60000
    };

    return new Promise((resolve, reject) => {
      axios
        .post(url, toFormData(param), config)
        .then(res => {
          resolve(res.data);
        })
        .catch(err => {
          reject(err);
        });
    });
  }
}
