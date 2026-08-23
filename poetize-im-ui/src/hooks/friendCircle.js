import {useDialog} from 'naive-ui';

import {ElMessage} from "element-plus";

import {reactive, getCurrentInstance} from 'vue';

export default function () {
  const globalProperties = getCurrentInstance().appContext.config.globalProperties;
  const $common = globalProperties.$common;
  const $http = globalProperties.$http;
  const $constant = globalProperties.$constant;
  const dialog = useDialog();

  let friendCircleData = reactive({
    showFriendCircle: false,
    treeHoleList: [],
    weiYanDialogVisible: false,
    loading: false,
    isPublic: true,
    weiYanAvatar: '',
    weiYanUsername: '',
    pagination: {
      current: 1,
      size: 10,
      total: 0,
      userId: null
    }
  })
  let feedRequestId = 0;

  function launch() {
    friendCircleData.weiYanDialogVisible = true;
  }

  function openFriendCircle(userId, avatar, username) {
    feedRequestId += 1;
    friendCircleData.pagination = {
      current: 1,
      size: 10,
      total: 0,
      userId
    };
    friendCircleData.treeHoleList = [];
    friendCircleData.loading = false;
    friendCircleData.weiYanAvatar = avatar;
    friendCircleData.weiYanUsername = username;
    friendCircleData.showFriendCircle = true;
    getWeiYan();
  }

  function deleteTreeHole(id) {
    dialog.error({
      title: '警告',
      content: '确定删除?',
      positiveText: '确定',
      onPositiveClick: () => {
        $http.get($constant.baseURL + "/weiYan/deleteWeiYan", {id: id})
          .then(() => {
            ElMessage({
              message: "删除成功！",
              type: 'success'
            });
            feedRequestId += 1;
            friendCircleData.loading = false;
            friendCircleData.pagination.current = 1;
            friendCircleData.pagination.size = 10;
            friendCircleData.pagination.total = 0;
            friendCircleData.treeHoleList = [];
            getWeiYan();
          })
          .catch((error) => {
            ElMessage({
              message: error.message,
              type: 'error'
            });
          });
      }
    });
  }

  function getWeiYan(previousPage = null) {
    if (friendCircleData.loading) {
      return;
    }
    const requestId = feedRequestId;
    friendCircleData.loading = true;
    $http.post($constant.baseURL + "/weiYan/listWeiYan", {...friendCircleData.pagination})
      .then((res) => {
        if (requestId !== feedRequestId) {
          return;
        }
        if (!$common.isEmpty(res.data)) {
          const records = Array.isArray(res.data.records) ? res.data.records : [];
          records.forEach(c => {
            c.content = $common.formatContent(c.content);
          });
          friendCircleData.treeHoleList = friendCircleData.treeHoleList.concat(records);
          friendCircleData.pagination.total = Number(res.data.total) || 0;
        }
      })
      .catch((error) => {
        if (requestId !== feedRequestId) {
          return;
        }
        if (previousPage !== null) {
          friendCircleData.pagination.current = previousPage;
        }
        ElMessage({
          message: error.message,
          type: 'error'
        });
      })
      .finally(() => {
        if (requestId === feedRequestId) {
          friendCircleData.loading = false;
        }
      });
  }

  function submitWeiYan(content) {
    let weiYan = {
      content: content,
      isPublic: friendCircleData.isPublic
    };

    $http.post($constant.baseURL + "/weiYan/saveWeiYan", weiYan)
      .then(() => {
        feedRequestId += 1;
        friendCircleData.loading = false;
        friendCircleData.pagination.current = 1;
        friendCircleData.pagination.size = 10;
        friendCircleData.pagination.total = 0;
        friendCircleData.treeHoleList = [];
        friendCircleData.weiYanDialogVisible = false;
        getWeiYan();
      })
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  function cleanFriendCircle() {
    feedRequestId += 1;
    friendCircleData.pagination = {
      current: 1,
      size: 10,
      total: 0,
      userId: null
    };
    friendCircleData.weiYanAvatar = '';
    friendCircleData.weiYanUsername = '';
    friendCircleData.treeHoleList = [];
    friendCircleData.loading = false;
    friendCircleData.showFriendCircle = false;
  }

  function pageWeiYan() {
    if (friendCircleData.loading || friendCircleData.treeHoleList.length >= friendCircleData.pagination.total) {
      return;
    }
    const previousPage = friendCircleData.pagination.current;
    friendCircleData.pagination.current = friendCircleData.pagination.current + 1;
    getWeiYan(previousPage);
  }

  function addFriend() {
    dialog.success({
      title: '好友申请',
      content: '确认提交好友申请，添加 ' + friendCircleData.weiYanUsername + ' 为好友？',
      positiveText: '确定',
      onPositiveClick: () => {
        $http.get($constant.baseURL + "/imChatUserFriend/addFriend", {friendId: friendCircleData.pagination.userId})
          .then(() => {
            ElMessage({
              message: "提交成功！",
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
    });
  }

  return {
    friendCircleData,
    launch,
    openFriendCircle,
    deleteTreeHole,
    submitWeiYan,
    pageWeiYan,
    cleanFriendCircle,
    addFriend
  }
}
