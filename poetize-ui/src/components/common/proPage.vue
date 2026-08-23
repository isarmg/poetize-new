<template>
  <div v-if="totalSize > 1" class="myCenter">
    <ul class="page-content">
      <li class="page-item" v-if="currentPage > 1" @click="toPage(currentPage - 1)">
        👈
      </li>
      <li class="page-item"
          :style="{background: page === currentPage ? color : '', color: page === currentPage ? 'var(--white)' : ''}"
          v-for="page in pageNumbers"
          :key="page"
          @click="toPage(page)">
        {{page}}
      </li>
      <li class="page-item" v-if="currentPage < totalSize" @click="toPage(currentPage + 1)">
        👉
      </li>
    </ul>
  </div>
</template>

<script>
  export default {
    emits: ["toPage"],
    props: {
      current: {
        type: Number,
        default: 1
      },
      size: {
        type: Number,
        default: 10
      },
      total: {
        type: Number,
        default: 0
      },
      buttonSize: {
        type: Number,
        default: 3
      },
      color: {
        type: String,
        default: ""
      }
    },

    computed: {
      normalizedSize() {
        return Number.isFinite(this.size) && this.size > 0 ? Math.floor(this.size) : 1;
      },
      totalSize() {
        const total = Number.isFinite(this.total) && this.total > 0 ? this.total : 0;
        return Math.ceil(total / this.normalizedSize);
      },
      currentPage() {
        if (this.totalSize === 0) return 1;
        const current = Number.isFinite(this.current) ? Math.floor(this.current) : 1;
        return Math.min(this.totalSize, Math.max(1, current));
      },
      realButtonSize() {
        const requested = Number.isFinite(this.buttonSize) ? Math.max(1, Math.floor(this.buttonSize)) : 1;
        return Math.min(requested, this.totalSize);
      },
      pageNumbers() {
        if (this.realButtonSize === 0) return [];
        const half = Math.floor(this.realButtonSize / 2);
        const latestStart = this.totalSize - this.realButtonSize + 1;
        const start = Math.max(1, Math.min(this.currentPage - half, latestStart));
        return Array.from({length: this.realButtonSize}, (_, index) => start + index);
      }
    },

    methods: {
      toPage(page) {
        const target = Math.min(this.totalSize, Math.max(1, page));
        if (target !== this.currentPage) {
          this.$emit("toPage", target);
        }
      }
    }
  }
</script>

<style scoped>

  .page-content {
    display: flex;
    padding: 0;
    margin: 15px 0;
  }

  .page-item {
    margin: 0 10px;
    list-style: none;
    border: 1px solid var(--lightGray);
    width: 40px;
    height: 40px;
    line-height: 38px;
    text-align: center;
    border-radius: 50%;
    color: var(--black);
    font-size: 14px;
    cursor: pointer;
  }

  .page-item:hover {
    border: 1px solid var(--themeBackground);
    box-shadow: 0 0 5px var(--themeBackground);
  }
</style>
