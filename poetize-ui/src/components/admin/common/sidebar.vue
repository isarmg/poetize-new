<template>
  <div class="sidebar">
    <div @click="collapse()" style="color: rgb(96, 98, 102);cursor: pointer;background-color: #ebf1f6;display: flex">
      <el-icon style="margin: 14px;font-size: 17px"><component :is="$icons.Menu" /></el-icon>
      <div style="font-size: 15px;margin-top: 13px">折叠</div>
    </div>
    <el-menu class="sidebar-el-menu"
             background-color="#ebf1f6"
             text-color="#606266"
             active-text-color="#20a0ff"
             unique-opened
             :default-active="$route.path"
             router>
      <template v-for="item in items" :key="item.index">
        <template v-if="isBoss || !item.isBoss">
          <template v-if="item.subs">
            <el-sub-menu :index="item.index">
              <template #title>
                <el-icon><component :is="$icons[item.icon]" /></el-icon>
                <span>{{ item.title }}</span>
              </template>
              <template v-for="subItem in item.subs" :key="subItem.index">
                <el-sub-menu v-if="subItem.subs" :index="subItem.index">
                  <template #title>
                    {{ subItem.title }}
                  </template>
                  <el-menu-item v-for="threeItem in subItem.subs" :key="threeItem.index" :index="threeItem.index">
                    {{ threeItem.title }}
                  </el-menu-item>
                </el-sub-menu>
                <el-menu-item v-else :index="subItem.index">
                  {{ subItem.title }}
                </el-menu-item>
              </template>
            </el-sub-menu>
          </template>
          <template v-else>
            <el-menu-item :index="item.index">
              <el-icon><component :is="$icons[item.icon]" /></el-icon>
              {{ item.title }}
            </el-menu-item>
          </template>
        </template>
      </template>
    </el-menu>
  </div>
</template>

<script>
  export default {
    data() {
      return {
        isCollapse: true,
        isBoss: this.$store.state.currentAdmin.isBoss,
        items: [{
          icon: "HomeFilled",
          index: "/main",
          title: "系统首页",
          isBoss: true
        }, {
          icon: "Tools",
          index: "/webEdit",
          title: "网站设置",
          isBoss: true
        }, {
          icon: "UserFilled",
          index: "/userList",
          title: "用户管理",
          isBoss: true
        }, {
          icon: "Postcard",
          index: "/postList",
          title: "文章管理",
          isBoss: false
        }, {
          icon: "Notebook",
          index: "/sortList",
          title: "分类管理",
          isBoss: true
        }, {
          icon: "Memo",
          index: "/configList",
          title: "配置管理",
          isBoss: true
        }, {
          icon: "EditPen",
          index: "/commentList",
          title: "评论管理",
          isBoss: false
        }, {
          icon: "Comment",
          index: "/treeHoleList",
          title: "留言管理",
          isBoss: true
        }, {
          icon: "Paperclip",
          index: "/resourceList",
          title: "资源管理",
          isBoss: true
        }, {
          icon: "CreditCard",
          index: "/resourcePathList",
          title: "资源聚合",
          isBoss: true
        }, {
          icon: "Sugar",
          index: "/loveList",
          title: "表白墙",
          isBoss: true
        }]
      }
    },

    computed: {},

    watch: {},

    created() {

    },

    mounted() {

    },

    methods: {
      collapse() {
        if (this.isCollapse) {
          $(".sidebar").css("width", "45px");
          $(".content-box").css("left", "45px");
        } else {
          $(".sidebar").css("width", "130px");
          $(".content-box").css("left", "130px");
        }
        this.isCollapse = !this.isCollapse;
      }
    }
  }
</script>

<style scoped>

  .sidebar {
    display: block;
    position: absolute;
    left: 0;
    top: 70px;
    bottom: 0;
    overflow-y: scroll;
    width: 130px;
    user-select: none;
  }

  .sidebar::-webkit-scrollbar {
    width: 0;
  }

  .sidebar > ul {
    height: 100%;
  }

  .sidebar-el-menu .el-menu-item {
    padding: 0 10px !important;
  }
</style>
