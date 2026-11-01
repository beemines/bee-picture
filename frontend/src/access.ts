import { useLoginUserStore } from '@/stores/useLoginUserStore'
import { message } from 'ant-design-vue'
import router from '@/router'

// 是否为首次获取登录用户
let firstFetchLoginUser = true

/**
 * 全局权限校验
 */
router.beforeEach(async (to, from, next) => {
  const loginUserStore = useLoginUserStore()
  let loginUser = loginUserStore.loginUser
  // 确保页面刷新，首次加载时，能够等后端返回用户信息后再校验权限
  if (firstFetchLoginUser && !loginUser.id) {
    const fetched = await loginUserStore.fetchLoginUser()
    loginUser = loginUserStore.loginUser
    firstFetchLoginUser = !fetched
    if (!fetched) {
      message.warning('暂时无法连接服务器，请稍后重试')
    }
  }
  // 登录接口已返回有效身份时，不再用额外的首次请求覆盖它。
  if (loginUser.id) {
    firstFetchLoginUser = false
  }
  const toUrl = to.fullPath
  if (toUrl.startsWith('/admin')) {
    if (!loginUser || loginUser.userRole !== 'admin') {
      message.error('没有权限')
      if (loginUser?.id) {
        next('/')
      } else {
        next({ path: '/user/login', query: { redirect: to.fullPath } })
      }
      return
    }
  }
  next()
})
