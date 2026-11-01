import { saveAs } from 'file-saver'

/**
 * 格式化文件大小
 * @param size
 */
export const formatSize = (size?: number) => {
  if (!size) return '未知'
  if (size < 1024) return size + ' B'
  if (size < 1024 * 1024) return (size / 1024).toFixed(2) + ' KB'
  return (size / (1024 * 1024)).toFixed(2) + ' MB'
}

/**
 * 下载图片
 * @param url 图片下载地址
 * @param fileName 要保存为的文件名
 */
export function downloadImage(url?: string, fileName?: string) {
  if (!url) {
    return
  }
  saveAs(url, fileName)
}

export function toHexColor(input: string) {
  // 去掉 0x 前缀
  const colorValue = input.startsWith('0x') ? input.slice(2) : input

  // 将剩余部分解析为十六进制数，再转成 6 位十六进制字符串
  const hexColor = parseInt(colorValue, 16).toString(16).padStart(6, '0')

  // 返回标准 #RRGGBB 格式
  return `#${hexColor}`
}

/**
 * 获取图片 blob 对象和 base64
 * @param url 图片 url
 * @param cb 回调函数,返回 blob url 和 base64
 */
export const fetchImageAsBlob = async (
  url?: string,
  cb?: (blobUrl: string, base64: string) => void,
) => {
  if (!url) return

  const imageOrigin = import.meta.env.VITE_IMAGE_ORIGIN
  const source = new URL(url, window.location.origin)
  const formatUrl = import.meta.env.DEV && imageOrigin && source.origin === new URL(imageOrigin).origin
    ? `/image-proxy${source.pathname}${source.search}` : url


  try {
    const response = await fetch(formatUrl)
    if (!response.ok) {
      throw new Error('图片加载失败')
    }

    const imageBlob = await response.blob()
    const objectUrl = URL.createObjectURL(imageBlob)

    const reader = new FileReader()
    reader.readAsDataURL(imageBlob)
    reader.onloadend = () => {
      const base64 = reader.result as string
      cb?.(objectUrl, base64)
    }
  } catch (error: any) {
    console.log(error)
  }
}
