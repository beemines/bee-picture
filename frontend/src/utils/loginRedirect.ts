/** 将登录返回地址规范为站内路由，兼容请求拦截器传入的同源完整 URL。 */
export function getSafeLoginRedirect(value: unknown, origin: string): string {
  if (
    typeof value !== 'string' ||
    value !== value.trim() ||
    /[\\\u0000-\u001f\u007f]|%5c/i.test(value) ||
    value.startsWith('//') ||
    !(value.startsWith('/') || /^https?:\/\//i.test(value))
  ) {
    return '/'
  }

  try {
    const url = new URL(value, origin)
    if (
      url.origin !== origin ||
      url.pathname.startsWith('//') ||
      url.username ||
      url.password ||
      url.pathname.replace(/\/$/, '') === '/user/login'
    ) {
      return '/'
    }
    return url.pathname + url.search + url.hash
  } catch {
    return '/'
  }
}
