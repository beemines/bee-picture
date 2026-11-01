import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getLoginUserUsingGet } from '@/api/userController.ts'

export const useLoginUserStore = defineStore('loginUser', () => {
  const loginUser = ref<API.LoginUserVO>({
    userName: '未登录',
  })

  // 返回是否已确认会话状态；服务不可用时保持访客状态，允许后续导航重试。
  async function fetchLoginUser(): Promise<boolean> {
    try {
      const res = await getLoginUserUsingGet()
      if (res.data.code === 0 && res.data.data?.id) {
        loginUser.value = res.data.data
        return true
      }
      loginUser.value = { userName: '未登录' }
      return res.data.code === 40100
    } catch {
      loginUser.value = { userName: '未登录' }
      return false
    }
  }

  function setLoginUser(newLoginUser: API.LoginUserVO) {
    loginUser.value = newLoginUser
  }

  return { loginUser, setLoginUser, fetchLoginUser }
})
