import {ElMessage} from "element-plus";

import {reactive, getCurrentInstance} from 'vue';

export default function () {
  const globalProperties = getCurrentInstance().appContext.config.globalProperties;
  const $common = globalProperties.$common;
  const $http = globalProperties.$http;
  const $constant = globalProperties.$constant;
  let groupData = reactive({
    //群组列表
    groups: {},
    //当前群信息
    currentGroupId: null
  })

  function exitGroup(currentGroupId, onRemoved) {
    $http.get($constant.baseURL + "/imChatGroupUser/quitGroup", {id: currentGroupId})
      .then(() => {
        delete groupData.groups[currentGroupId];
        groupData.currentGroupId = null;
        if (typeof onRemoved === 'function') {
          onRemoved(currentGroupId);
        }
        ElMessage({
          message: "退群成功！",
          type: 'success'
        });
      })
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  function dissolveGroup(currentGroupId, onRemoved) {
    $http.get($constant.baseURL + "/imChatGroup/deleteGroup", {id: currentGroupId})
      .then(() => {
        delete groupData.groups[currentGroupId];
        groupData.currentGroupId = null;
        if (typeof onRemoved === 'function') {
          onRemoved(currentGroupId);
        }
        ElMessage({
          message: "解散群成功！",
          type: 'success'
        });
      })
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  async function getImGroup() {
    await $http.get($constant.baseURL + "/imChatGroup/listGroup")
      .then((res) => {
        if (!$common.isEmpty(res.data)) {
          res.data.forEach(group => {
            groupData.groups[group.id] = group;
          });
        }
      })
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  function addGroupTopic() {
    $http.get($constant.baseURL + "/imChatGroup/addGroupTopic", {id: groupData.currentGroupId})
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  return {
    groupData,
    getImGroup,
    addGroupTopic,
    exitGroup,
    dissolveGroup
  }
}
