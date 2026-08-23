import axios from "axios";
import constant from "./constant";
//处理url参数
import qs from "qs";

import store from "../store";
import {clearToken, getValidToken} from "./auth";


axios.defaults.baseURL = constant.baseURL;


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
  const payload = response.data;
  if (payload !== null && typeof payload === "object" && Object.prototype.hasOwnProperty.call(payload, "code") && payload.code !== 200) {
    if (response.data.code === 300) {
      const isAdminRequest = response.config?.poetizeAdmin === true;
      if (isAdminRequest) {
        store.commit("loadCurrentAdmin", {});
        clearToken("adminToken");
      } else {
        store.commit("loadCurrentUser", {});
        clearToken("userToken");
      }
      window.location.assign(constant.webURL + (isAdminRequest ? "/verify" : "/user"));
    }
    const message = typeof response.data.message === "string" && response.data.message
      ? response.data.message
      : "请求失败";
    return Promise.reject(new Error(message));
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
  post(url, params = {}, isAdmin = false, json = true) {
    let config;
    if (isAdmin) {
      config = {
        headers: {"Authorization": getValidToken("adminToken")},
        poetizeAdmin: true
      };
    } else {
      config = {
        headers: {"Authorization": getValidToken("userToken")},
        poetizeAdmin: false
      };
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

  get(url, params = {}, isAdmin = false) {
    let headers;
    if (isAdmin) {
      headers = {"Authorization": getValidToken("adminToken")};
    } else {
      headers = {"Authorization": getValidToken("userToken")};
    }

    return new Promise((resolve, reject) => {
      axios.get(url, {
        params: params,
        headers: headers,
        poetizeAdmin: isAdmin
      }).then(res => {
        resolve(res.data);
      }).catch(err => {
        reject(err)
      })
    });
  },

  upload(url, param, isAdmin = false, option) {
    let config;
    if (isAdmin) {
      config = {
        headers: {"Authorization": getValidToken("adminToken")},
        timeout: 60000,
        poetizeAdmin: true
      };
    } else {
      config = {
        headers: {"Authorization": getValidToken("userToken")},
        timeout: 60000,
        poetizeAdmin: false
      };
    }
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
        .post(url, param, config)
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
        .post(url, param, config)
        .then(res => {
          resolve(res.data);
        })
        .catch(err => {
          reject(err);
        });
    });
  }
}
