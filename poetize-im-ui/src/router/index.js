import {createRouter, createWebHistory} from 'vue-router'

const routes = [
  {
    path: "/",
    meta: {requiresAuth: true},
    component: () => import('../components/index.vue')
  },
  {
    path: '/:pathMatch(.*)*',
    redirect: '/'
  }
]

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes,
  scrollBehavior() {
    return {left: 0, top: 0};
  }
})

export default router
