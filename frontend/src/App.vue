<template>
  <a-config-provider :locale="zhCN">
    <div id="app">
      <BasicLayout />
    </div>
  </a-config-provider>
</template>

<script setup lang="ts">
//import { healthUsingGet } from '@/api/mainController'
//import { useLoginUserStore } from '@/stores/useLoginUserStore.ts'

//已经改为在权限校验文件中获取
//const loginUserStore = useLoginUserStore();
//loginUserStore.fetchLoginUser()

//healthUsingGet().then((res) => {
//  console.log(res)
//})

//国际化 能让分页里边的Page变成页，编辑/添加弹窗的cancel变成取消等
import BasicLayout from '@/layouts/BasicLayout.vue'
import zhCN from 'ant-design-vue/es/locale/zh_CN'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'

// 设置 dayjs 为中文
dayjs.locale('zh-cn')
</script>

<style scoped>

</style>
