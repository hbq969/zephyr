import { createRouter, createWebHashHistory } from 'vue-router'

const router = createRouter({
  history: createWebHashHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/',
      redirect: '/chat'
    },
    {
      path: '/chat',
      name: 'Chat',
      component: () => import('../views/chat/ChatView.vue'),
    },
    {
      path: '/settings/mcp',
      name: 'MCPSettings',
      component: () => import('../views/settings/MCPSettings.vue'),
    },
    {
      path: '/settings/skills',
      name: 'SkillSettings',
      component: () => import('../views/settings/SkillSettings.vue'),
    },
    {
      path: '/settings/model',
      name: 'ModelSettings',
      component: () => import('../views/settings/ModelSettings.vue'),
    },
    {
      path: '/settings/memory',
      name: 'MemorySettings',
      component: () => import('../views/settings/MemorySettings.vue'),
    },
  ],
})

export default router
