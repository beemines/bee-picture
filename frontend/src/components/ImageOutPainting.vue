<template>
  <a-modal
    class="image-out-painting"
    v-model:open="visible"
    title="AI 扩图"
    :footer="false"
    @cancel="closeModal"
  >
    <a-row gutter="16">
      <a-col span="12">
        <h4>原始图片</h4>
        <img :src="picture?.url" :alt="picture?.name" style="max-width: 100%" />
      </a-col>
      <a-col span="12">
        <h4>扩图结果</h4>
        <img
          v-if="resultImageUrl"
          :src="resultImageUrl"
          :alt="picture?.name"
          style="max-width: 100%"
        />
      </a-col>
    </a-row>
    <div style="margin-bottom: 16px" />
    <a-flex gap="16" justify="center">
      <a-button type="primary" :loading="creatingTask || !!taskId" :disabled="uploadLoading" ghost @click="createTask"> 生成图片 </a-button>
      <a-button type="primary" v-if="resultImageUrl" :loading="uploadLoading" @click="handleUpload">
        应用结果
      </a-button>
    </a-flex>
  </a-modal>
</template>

<script setup lang="ts">
import { onUnmounted, ref } from 'vue'
import {
  createPictureOutPaintingTaskUsingPost,
  getPictureOutPaintingTaskUsingGet,
  uploadPictureByUrlUsingPost,
} from '@/api/pictureController'
import { message } from 'ant-design-vue'

interface Props {
  picture?: API.PictureVO
  spaceId?: API.ID
  onSuccess?: (newPicture: API.PictureVO) => void
}

const resultImageUrl = ref<string>()
const visible = ref(false)
const creatingTask = ref(false)
const uploadLoading = ref(false)

const props = defineProps<Props>()

// 任务 id
const taskId = ref<string>()
// 关闭或开启新任务时递增，防止旧请求更新已关闭或重新打开的弹窗。
let requestVersion = 0
const isCurrentRequest = (version: number) => visible.value && version === requestVersion

/**
 * 创建任务
 */
const createTask = async () => {
  if (!visible.value || creatingTask.value || taskId.value || uploadLoading.value || !props.picture?.id) {
    return
  }
  const version = ++requestVersion
  creatingTask.value = true
  resultImageUrl.value = undefined
  try {
    const res = await createPictureOutPaintingTaskUsingPost({
      pictureId: props.picture.id,
      parameters: {
        xScale: 2,
        yScale: 2,
      },
    })
    if (!isCurrentRequest(version)) return
    if (res.data.code === 0 && res.data.data?.output?.taskId) {
      message.success('创建任务成功，请耐心等待')
      taskId.value = res.data.data.output.taskId
      startPolling(version)
    } else {
      message.error('创建任务失败，' + (res.data.message || '请稍后重试'))
    }
  } catch {
    if (isCurrentRequest(version)) {
      message.error('创建任务失败，服务器暂时不可用，请稍后重试')
    }
  } finally {
    if (isCurrentRequest(version)) creatingTask.value = false
  }
}

// 轮询定时器
let pollingTimer: ReturnType<typeof setInterval> | null = null

// 清理轮询定时器
const clearPolling = () => {
  if (pollingTimer !== null) {
    clearInterval(pollingTimer)
    pollingTimer = null
  }
  taskId.value = undefined
}

// 开始轮询
const startPolling = (version: number) => {
  const currentTaskId = taskId.value
  if (!currentTaskId) return
  let polling = false

  pollingTimer = setInterval(async () => {
    if (polling || !isCurrentRequest(version)) return
    polling = true
    try {
      const res = await getPictureOutPaintingTaskUsingGet({
        taskId: currentTaskId,
      })
      if (!isCurrentRequest(version)) return
      const taskResult = res.data.data?.output
      if (res.data.code !== 0 || !taskResult) {
        message.error('检测任务状态失败，' + (res.data.message || '请稍后重试'))
        clearPolling()
      } else if (taskResult.taskStatus === 'SUCCEEDED') {
        if (taskResult.outputImageUrl) {
          resultImageUrl.value = taskResult.outputImageUrl
          message.success('扩图任务成功')
        } else {
          message.error('扩图任务未返回结果图片，请重试')
        }
        clearPolling()
      } else if (taskResult.taskStatus === 'FAILED') {
        message.error('扩图任务失败')
        clearPolling()
      } else if (taskResult.taskStatus !== 'PENDING' && taskResult.taskStatus !== 'RUNNING') {
        message.error(taskResult.taskStatus === 'CANCELED' ? '扩图任务已取消' : '扩图任务状态异常，请重试')
        clearPolling()
      }
    } catch {
      if (isCurrentRequest(version)) {
        message.error('检测任务状态失败，请稍后重试')
        clearPolling()
      }
    } finally {
      polling = false
    }
  }, 3000) // 每隔 3 秒轮询一次
}

// 清理定时器，避免内存泄漏
onUnmounted(() => {
  closeModal()
})

const handleUpload = async () => {
  if (!visible.value || !resultImageUrl.value || uploadLoading.value || creatingTask.value || taskId.value) return
  const version = requestVersion
  uploadLoading.value = true
  try {
    const params: API.PictureUploadRequest = {
      fileUrl: resultImageUrl.value,
      spaceId: props.spaceId,
    }
    if (props.picture) {
      params.id = props.picture.id
    }
    const res = await uploadPictureByUrlUsingPost(params)
    if (!isCurrentRequest(version)) return
    if (res.data.code === 0 && res.data.data) {
      message.success('图片上传成功')
      // 将上传成功的图片信息传递给父组件
      props.onSuccess?.(res.data.data)
      // 关闭弹窗
      closeModal()
    } else {
      message.error('图片上传失败，' + res.data.message)
    }
  } catch {
    if (isCurrentRequest(version)) message.error('图片上传失败，请稍后重试')
  } finally {
    if (isCurrentRequest(version)) uploadLoading.value = false
  }
}

// 打开弹窗
const openModal = () => {
  visible.value = true
}

// 关闭弹窗
const closeModal = () => {
  visible.value = false
  requestVersion++
  clearPolling()
  creatingTask.value = false
  uploadLoading.value = false
  resultImageUrl.value = undefined
}

// 暴露函数给父组件
defineExpose({
  openModal,
})
</script>

<style scoped>
.image-out-painting {
  text-align: center;
}
</style>
