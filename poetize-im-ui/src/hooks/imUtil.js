import $ from 'jquery';

import {ElMessage} from "element-plus";

import {reactive, getCurrentInstance, onMounted, onBeforeUnmount} from 'vue';

export default function () {
  const globalProperties = getCurrentInstance().appContext.config.globalProperties;
  const $common = globalProperties.$common;
  const $http = globalProperties.$http;
  const $constant = globalProperties.$constant;
  let imUtilData = reactive({
    //系统消息
    systemMessages: [],
    showBodyLeft: true,
    //表情包
    imageList: []
  })

  onMounted(() => {
    if ($common.mobile()) {
      $(".friend-aside").off("click.imUtilMobile").on("click.imUtilMobile", function () {
        imUtilData.showBodyLeft = true;
        mobileRight();
      });

      $(".body-right").off("click.imUtilMobile").on("click.imUtilMobile", function () {
        imUtilData.showBodyLeft = false;
        mobileRight();
      });
    }
    mobileRight();
  })

  onBeforeUnmount(() => {
    $(".friend-aside").off(".imUtilMobile");
    $(".body-right").off(".imUtilMobile");
    $(".message img").off(".imUtilImage");
    $("#outerImg").off(".imUtilImage");
  })

  function changeAside() {
    imUtilData.showBodyLeft = !imUtilData.showBodyLeft;
    mobileRight();
  }

  function mobileRight() {
    if (imUtilData.showBodyLeft && $common.mobile()) {
      $(".body-right").addClass("mobile-right");
    } else {
      $(".body-right").removeClass("mobile-right");
    }
  }

  function getSystemMessages() {
    $http.get($constant.baseURL + "/imChatUserMessage/listSystemMessage")
      .then((res) => {
        if (!$common.isEmpty(res.data) && !$common.isEmpty(res.data.records)) {
          imUtilData.systemMessages = res.data.records;
        }
      })
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  function hiddenBodyLeft() {
    if ($common.mobile()) {
      $(".body-right").off("click.imUtilMobile").on("click.imUtilMobile", function () {
        imUtilData.showBodyLeft = false;
        mobileRight();
      });
    }
  }

  function imgShow() {
    $(".message img").off("click.imUtilImage").on("click.imUtilImage", function () {
      let src = $(this).attr("src");
      $("#bigImg").attr("src", src);

      /** 获取当前点击图片的真实大小，并显示弹出层及大图 */
      $("<img/>").on("load", function () {
        let windowW = $(window).width();//获取当前窗口宽度
        let windowH = $(window).height();//获取当前窗口高度
        let realWidth = this.width;//获取图片真实宽度
        let realHeight = this.height;//获取图片真实高度
        let imgWidth, imgHeight;
        let scale = 0.8;//缩放尺寸，当图片真实宽度和高度大于窗口宽度和高度时进行缩放

        if (realHeight > windowH * scale) {//判断图片高度
          imgHeight = windowH * scale;//如大于窗口高度，图片高度进行缩放
          imgWidth = imgHeight / realHeight * realWidth;//等比例缩放宽度
          if (imgWidth > windowW * scale) {//如宽度仍大于窗口宽度
            imgWidth = windowW * scale;//再对宽度进行缩放
          }
        } else if (realWidth > windowW * scale) {//如图片高度合适，判断图片宽度
          imgWidth = windowW * scale;//如大于窗口宽度，图片宽度进行缩放
          imgHeight = imgWidth / realWidth * realHeight;//等比例缩放高度
        } else {//如果图片真实高度和宽度都符合要求，高宽不变
          imgWidth = realWidth;
          imgHeight = realHeight;
        }
        $("#bigImg").css("width", imgWidth + "px");//以最终的宽度对图片缩放

        let w = (windowW - imgWidth) / 2;//计算图片与窗口左边距
        let h = (windowH - imgHeight) / 2;//计算图片与窗口上边距
        $("#innerImg").css({"top": h + "px", "left": w + "px"});//设置top和left属性
        $("#outerImg").fadeIn("fast");//淡入显示
      }).attr("src", src);

      $("#outerImg").off("click.imUtilImage").on("click.imUtilImage", function () {//再次点击淡出消失弹出层
        $(this).fadeOut("fast");
      });
    });
  }

  function getImageList() {
    $http.get($constant.baseURL + "/resource/getImageList")
      .then((res) => {
        if (!$common.isEmpty(res.data)) {
          imUtilData.imageList = res.data;
        }
      })
      .catch((error) => {
        ElMessage({
          message: error.message,
          type: 'error'
        });
      });
  }

  function parseMessage(content) {
    return $common.formatContent(content);
  }

  return {
    imUtilData,
    changeAside,
    mobileRight,
    getSystemMessages,
    hiddenBodyLeft,
    imgShow,
    getImageList,
    parseMessage
  }
}
