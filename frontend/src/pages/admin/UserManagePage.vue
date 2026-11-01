<template>
  <div id="userManagePage">
    <a-flex justify="space-between">
      <h2>用户管理</h2>
      <!-- 添加用户-->
      <div id="addUser"><a-button type="primary" class="add-btn" @click="doAdd">添加用户</a-button></div>
    </a-flex>
    <!--搜索表单-->
    <a-form layout="inline" :model="searchParams" style="margin-bottom: 8px" @finish="doSearch">
      <a-form-item label="账号">
        <a-input v-model:value="searchParams.userAccount" placeholder="输入账号" />
      </a-form-item>
      <a-form-item label="用户名">
        <a-input v-model:value="searchParams.userName" placeholder="输入用户名" />
      </a-form-item>
      <a-form-item>
        <a-button type="primary" html-type="submit">搜索</a-button>
      </a-form-item>
    </a-form>
    <!--表格-->
    <a-table
      :columns="columns"
      :data-source="dataList"
      :pagination="pagination"
      @change="doTableChange"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.dataIndex === 'userAvatar'">
          <a-image :src="record.userAvatar" :width="120" />
        </template>
        <template v-else-if="column.dataIndex === 'userRole'">
          <div v-if="record.userRole === 'admin'">
            <a-tag color="green">管理员</a-tag>
          </div>
          <div v-else>
            <a-tag color="blue">普通用户</a-tag>
          </div>
        </template>
        <template v-else-if="column.dataIndex === 'createTime'">
          {{ dayjs(record.createTime).format('YYYY-MM-DD HH:mm:ss') }}
        </template>
        <template v-else-if="column.key === 'action'">
          <a-space>
            <a-button class="edit-btn" @click="doUpdate(record)">编辑</a-button>
            <a-popconfirm
              title="确定要删除这个用户吗？"
              ok-text="确定"
              cancel-text="取消"
              @confirm="doDelete(record.id)"
            >
              <a-button danger>删除</a-button>
            </a-popconfirm>
          </a-space>
        </template>
      </template>
    </a-table>

    <!-- 添加 / 编辑弹窗 -->
    <a-modal
      v-model:open="open"
      :title="isEdit ? '编辑用户' : '添加用户'"
      @ok="handleModalOk"
      @cancel="handleModalCancel"
    >
      <a-form :model="formState" layout="vertical">
        <a-form-item label="账号">
          <a-input
            v-model:value="formState.userAccount"
            placeholder="请输入账号"
            :disabled="isEdit"
          />
        </a-form-item>
        <a-form-item label="用户名">
          <a-input v-model:value="formState.userName" placeholder="请输入用户名" />
        </a-form-item>

        <a-form-item label="头像">
          <a-input v-model:value="formState.userAvatar" placeholder="请输入头像地址" />
        </a-form-item>

        <a-form-item label="简介">
          <a-input v-model:value="formState.userProfile" placeholder="请输入简介" />
        </a-form-item>

        <a-form-item label="角色">
          <a-select
            v-model:value="formState.userRole"
            :options="[
              { label: '普通用户', value: 'user' },
              { label: '管理员', value: 'admin' },
            ]"
            placeholder="请选择角色"
          />
        </a-form-item>
      </a-form>
    </a-modal>
  </div>
</template>
<script lang="ts" setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  addUserUsingPost,
  deleteUserUsingPost,
  listUserVoByPageUsingPost,
  updateUserUsingPost,
} from '@/api/userController.ts'
import { message } from 'ant-design-vue'
import dayjs from 'dayjs'

const columns = [
  {
    title: 'id',
    dataIndex: 'id',
  },
  {
    title: '账号',
    dataIndex: 'userAccount',
  },
  {
    title: '用户名',
    dataIndex: 'userName',
  },
  {
    title: '头像',
    dataIndex: 'userAvatar',
  },
  {
    title: '简介',
    dataIndex: 'userProfile',
  },
  {
    title: '用户角色',
    dataIndex: 'userRole',
  },
  {
    title: '创建时间',
    dataIndex: 'createTime',
  },
  {
    title: '操作',
    key: 'action',
  },
]

// 数据
const dataList = ref<API.UserVO[]>([])
const total = ref(0)

// 搜索条件
const searchParams = reactive<API.UserQueryRequest>({
  current: 1,
  pageSize: 10,
})

// 弹窗开关
const open = ref(false)

// 是否为编辑
const isEdit = ref(false)

// 表单数据
const formState = reactive<API.UserAddRequest & API.UserUpdateRequest>({
  id: undefined,
  userAccount: '',
  userName: '',
  userAvatar: '',
  userProfile: '',
  userRole: 'user',
})

// 重置表单
const resetForm = () => {
  formState.id = undefined
  formState.userAccount = ''
  formState.userName = ''
  formState.userAvatar = ''
  formState.userProfile = ''
  formState.userRole = 'user'
}

// 获取数据
const fetchData = async () => {
  const res = await listUserVoByPageUsingPost({
    ...searchParams,
  })
  if (res.data.code === 0 && res.data.data) {
    dataList.value = res.data.data.records ?? []
    total.value = res.data.data.total ?? 0
  } else {
    message.error('获取数据失败，' + res.data.message)
  }
}

// 页面加载时请求一次
onMounted(() => {
  fetchData()
})

// 分页参数
const pagination = computed(() => {
  return {
    current: searchParams.current ?? 1,
    pageSize: searchParams.pageSize ?? 10,
    total: total.value,
    showSizeChanger: true,
    showTotal: (total: number) => `共 ${total} 条`,
  }
})

// 表格变化处理
const doTableChange = (page: any) => {
  searchParams.current = page.current
  searchParams.pageSize = page.pageSize
  fetchData()
}

// 搜索
const doSearch = () => {
  searchParams.current = 1
  fetchData()
}

// 删除数据
const doDelete = async (id: API.ID) => {
  if (!id) {
    return
  }
  const res = await deleteUserUsingPost({ id })
  if (res.data.code === 0) {
    message.success('删除成功')
    fetchData()
  } else {
    message.error('删除失败：' + res.data.message)
  }
}

// 添加按钮
const doAdd = () => {
  resetForm()
  isEdit.value = false
  open.value = true
}

// 编辑按钮
const doUpdate = (record: API.UserVO) => {
  resetForm()
  isEdit.value = true

  formState.id = record.id
  formState.userAccount = record.userAccount ?? ''
  formState.userName = record.userName ?? ''
  formState.userAvatar = record.userAvatar ?? ''
  formState.userProfile = record.userProfile ?? ''
  formState.userRole = record.userRole ?? 'user'

  open.value = true
}

// 弹窗确定
const handleModalOk = async () => {
  if (isEdit.value) {
    const res = await updateUserUsingPost({
      id: formState.id,
      userName: formState.userName,
      userAvatar: formState.userAvatar,
      userProfile: formState.userProfile,
      userRole: formState.userRole,
    })

    if (res.data.code === 0) {
      message.success('编辑成功')
      open.value = false
      fetchData()
    } else {
      message.error('编辑失败：' + res.data.message)
    }
  } else {
    const res = await addUserUsingPost({
      userAccount: formState.userAccount,
      userName: formState.userName,
      userAvatar: formState.userAvatar,
      userProfile: formState.userProfile,
      userRole: formState.userRole,
    })

    if (res.data.code === 0) {
      message.success('添加成功')
      open.value = false
      fetchData()
    } else {
      message.error('添加失败：' + res.data.message)
    }
  }
}

// 弹窗取消
const handleModalCancel = () => {
  open.value = false
}
</script>

<style scoped>
#addUser {
  display: flex;
  justify-content: end;
  margin-bottom: 8px;
}

.add-btn {
  background-color: yellowgreen;
}

.edit-btn {
  margin-left: 8px;
}
</style>
