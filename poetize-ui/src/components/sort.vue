<template>
  <div>
    <!-- 两句诗 -->
    <div class="my-animation-slide-top">
      <twoPoem></twoPoem>
    </div>

    <div style="background: var(--background);padding-top: 40px;" class="my-animation-slide-bottom">
      <!-- 标签 -->
      <div class="sort-warp shadow-box" v-if="!$common.isEmpty(sort) && !$common.isEmpty(sort.labels)">
        <div v-for="(label, index) in sort.labels" :key="index"
             :class="{isActive: !$common.isEmpty(labelId) && parseInt(labelId) === label.id}"
             @click="listArticle(label)">
          <proTag :info="label.labelName+' '+label.countOfLabel"
                  :color="$constant.before_color_list[Math.floor(Math.random() * 6)]"
                  style="margin: 12px">
          </proTag>
        </div>
      </div>

      <!-- 文章 -->
      <div class="article-wrap">
        <articleList :articleList="articles"></articleList>
        <div class="pagination-wrap">
          <div @click="pageArticles()" class="pagination" v-if="pagination.total !== articles.length">
            下一页
          </div>
          <div v-else style="user-select: none">
            ~~到底啦~~
          </div>
        </div>
      </div>
      <!-- 页脚 -->
      <myFooter></myFooter>
    </div>
  </div>
</template>

<script>
  import {defineAsyncComponent} from 'vue';
  const twoPoem = defineAsyncComponent(() => import("./common/twoPoem.vue"));
  const proTag = defineAsyncComponent(() => import("./common/proTag.vue"));
  const articleList = defineAsyncComponent(() => import("./articleList.vue"));
  const myFooter = defineAsyncComponent(() => import("./common/myFooter.vue"));

  export default {
    components: {
      twoPoem,
      proTag,
      articleList,
      myFooter
    },

    data() {
      return {
        sortId: this.$route.query.sortId,
        labelId: this.$route.query.labelId,
        sort: null,
        pagination: {
          current: 1,
          size: 10,
          total: 0,
          searchKey: "",
          sortId: this.$route.query.sortId,
          labelId: this.$route.query.labelId
        },
        articles: [],
        articlesLoading: false,
        articleRequestId: 0
      }
    },

    computed: {
      storeSortInfo() {
        return this.$store.state.sortInfo;
      }
    },

    watch: {
      $route() {
        this.pagination = {
          current: 1,
          size: 10,
          total: 0,
          searchKey: "",
          sortId: this.$route.query.sortId,
          labelId: this.$route.query.labelId
        };
        this.articles.splice(0, this.articles.length);
        this.sortId = this.$route.query.sortId;
        this.labelId = this.$route.query.labelId;
        this.getSort();
        this.getArticles(1);
      },
      storeSortInfo() {
        this.getSort();
      }
    },

    created() {
      this.getSort();
      this.getArticles();
    },

    mounted() {
    },

    beforeUnmount() {
      this.articleRequestId += 1;
    },

    methods: {
      pageArticles() {
        if (this.articlesLoading || (this.pagination.total > 0 && this.articles.length >= this.pagination.total)) {
          return;
        }
        this.getArticles(this.pagination.current + 1);
      },

      getSort() {
        let sortInfo = this.$store.state.sortInfo;
        this.sort = null;
        if (!this.$common.isEmpty(sortInfo)) {
          let sortArray = sortInfo.filter(f => {
            return f.id === Number.parseInt(String(this.sortId), 10);
          });
          if (!this.$common.isEmpty(sortArray)) {
            this.sort = sortArray[0];
          }
        }
      },
      listArticle(label) {
        this.labelId = label.id;
        this.pagination = {
          current: 1,
          size: 10,
          total: 0,
          searchKey: "",
          sortId: this.$route.query.sortId,
          labelId: label.id
        };
        this.articles.splice(0, this.articles.length);
        this.$nextTick(() => {
          this.getArticles(1);
        });
      },
      async getArticles(page = this.pagination.current) {
        const requestId = ++this.articleRequestId;
        const pagination = {...this.pagination, current: page};
        this.articlesLoading = true;
        try {
          const res = await this.$http.post(this.$constant.baseURL + "/article/listArticle", pagination);
          if (requestId !== this.articleRequestId) {
            return false;
          }
          const records = Array.isArray(res.data?.records) ? res.data.records : [];
          this.articles = page === 1 ? records : this.articles.concat(records);
          this.pagination.current = page;
          this.pagination.total = Number(res.data?.total) || 0;
          return true;
        } catch (error) {
          if (requestId === this.articleRequestId) {
            this.$message({
              message: error?.message || "文章列表加载失败！",
              type: "error"
            });
          }
          return false;
        } finally {
          if (requestId === this.articleRequestId) {
            this.articlesLoading = false;
          }
        }
      }
    }
  }
</script>

<style scoped>

  .sort-warp {
    width: 70%;
    max-width: 780px;
    margin: 0 auto;
    padding: 20px;
    border-radius: 10px;
    display: flex;
    flex-wrap: wrap;
  }

  .article-wrap {
    width: 70%;
    margin: 40px auto;
    min-height: 600px;
  }

  .isActive {
    animation: scale 1.5s ease-in-out infinite;
  }

  .pagination-wrap {
    display: flex;
    justify-content: center;
    margin-top: 40px;
  }

  .pagination {
    padding: 13px 15px;
    border: 1px solid var(--lightGray);
    border-radius: 3rem;
    color: var(--greyFont);
    width: 100px;
    user-select: none;
    cursor: pointer;
    text-align: center;
  }

  .pagination:hover {
    border: 1px solid var(--themeBackground);
    color: var(--themeBackground);
    box-shadow: 0 0 5px var(--themeBackground);
  }


  @media screen and (max-width: 900px) {
    .sort-warp {
      width: 90%;
    }

    .article-wrap {
      width: 90%;
    }
  }
</style>
