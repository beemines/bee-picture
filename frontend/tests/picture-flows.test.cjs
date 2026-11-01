/* eslint-disable @typescript-eslint/no-require-imports -- Node harness exercises actual compiled SFC setup code. */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const vue = require('vue')
const { loadSource } = require('./helpers/load-source.cjs')

function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function createPicturePage(request, id = '7') {
  const errors = [], successes = [], destinations = []
  const component = loadSource('src/pages/user/AddPicturePage.vue', {
    vue: { ...vue, onMounted() {}, watchEffect() {} },
    'vue-router': {
      useRoute: () => ({ query: { id, spaceId: '12' } }),
      useRouter: () => ({ push: (destination) => destinations.push(destination) }),
    },
    'ant-design-vue': { message: { error: (text) => errors.push(text), success: (text) => successes.push(text) } },
    '@/api/pictureController.ts': { editPictureUsingPost: request },
    '@/api/spaceController.ts': {},
    '@/components/PictureUpload.vue': {}, '@/components/UrlPictureUpload.vue': {},
    '@/components/ImageCropper.vue': {}, '@/components/ImageOutPainting.vue': {},
    '@ant-design/icons-vue': {},
  }).default
  const page = component.setup({}, { expose() {} })
  page.picture.value = { id: '7', name: 'Original' }
  Object.assign(page.pictureForm, { name: 'Unsaved title', introduction: 'Unsaved text', tags: ['keep'] })
  return { page, errors, successes, destinations }
}

function createOutPainting({ create, poll, upload } = {}) {
  const errors = [], successes = [], applied = [], unmountCallbacks = []
  const timers = new Map()
  let nextTimer = 0
  const timerApi = {
    setInterval: (callback) => { timers.set(++nextTimer, callback); return nextTimer },
    clearInterval: (timer) => timers.delete(timer),
  }
  const createRequests = [], pollRequests = [], uploadRequests = []
  const component = loadSource('src/components/ImageOutPainting.vue', {
    vue: { ...vue, onUnmounted: (callback) => unmountCallbacks.push(callback) },
    '@/api/pictureController': {
      createPictureOutPaintingTaskUsingPost: (body) => {
        createRequests.push(body)
        return create ? create(body) : Promise.resolve({ data: { code: 0, data: { output: { taskId: 'task-1' } } } })
      },
      getPictureOutPaintingTaskUsingGet: (params) => {
        pollRequests.push(params)
        return poll ? poll(params) : Promise.resolve({ data: { code: 0, data: { output: { taskStatus: 'RUNNING' } } } })
      },
      uploadPictureByUrlUsingPost: (body) => {
        uploadRequests.push(body)
        return upload ? upload(body) : Promise.resolve({ data: { code: 0, data: { id: '7', url: '/expanded.png' } } })
      },
    },
    'ant-design-vue': { message: { error: (text) => errors.push(text), success: (text) => successes.push(text) } },
  }, timerApi).default
  const page = component.setup({ picture: { id: '7', url: '/original.png' }, spaceId: '12', onSuccess: (picture) => applied.push(picture) }, { expose() {} })
  return {
    page, errors, successes, applied, timers, createRequests, pollRequests, uploadRequests,
    tick: () => Promise.all([...timers.values()].map((callback) => callback())),
    unmount: () => unmountCallbacks.forEach((callback) => callback()),
  }
}

test('picture save failures preserve unsaved metadata and give actionable feedback', async () => {
  const { page, errors, successes, destinations } = createPicturePage(async () => { throw new Error('HTTP 500') })
  await page.handleSubmit({ ...page.pictureForm })
  assert.equal(errors.length, 1)
  assert.match(errors[0], /保存失败/)
  assert.equal(page.submitting.value, false)
  assert.deepEqual({ ...page.pictureForm }, { name: 'Unsaved title', introduction: 'Unsaved text', tags: ['keep'] })
  assert.deepEqual(successes, [])
  assert.deepEqual(destinations, [])
})

test('picture save is pending immediately and ignores duplicate submits', async () => {
  const response = deferred(), requests = []
  const { page } = createPicturePage((body) => { requests.push(body); return response.promise })
  const first = page.handleSubmit({ ...page.pictureForm })
  const second = page.handleSubmit({ ...page.pictureForm })
  try {
    assert.equal(page.submitting?.value, true)
    assert.equal(requests.length, 1)
    assert.equal(requests[0].id, '7')
    assert.equal(requests[0].spaceId, '12')
  } finally {
    response.resolve({ data: { code: 0, data: true } })
    await Promise.all([first, second])
  }
  assert.equal(page.submitting.value, false)
})

for (const [id, label, successText] of [['7', '保存修改', '保存成功'], [null, '创建', '创建成功']]) {
  test(`picture save uses accurate ${label} copy`, async () => {
    const { page, successes, destinations } = createPicturePage(async () => ({ data: { code: 0, data: true } }), id)
    await page.handleSubmit({ ...page.pictureForm })
    assert.equal(page.submitText?.value, label)
    assert.deepEqual(successes, [successText])
    assert.deepEqual(destinations, [{ path: '/picture/7' }])
  })
}

test('picture business failures keep the form open and reset pending state', async () => {
  const { page, errors, destinations } = createPicturePage(async () => ({ data: { code: 50000, message: '保存被拒绝' } }))
  await page.handleSubmit({ ...page.pictureForm })
  assert.match(errors[0], /保存失败.*保存被拒绝/)
  assert.equal(page.submitting.value, false)
  assert.equal(page.pictureForm.name, 'Unsaved title')
  assert.deepEqual(destinations, [])
})

test('outpainting blocks duplicate paid creation while the first response is pending', async () => {
  const response = deferred()
  const flow = createOutPainting({ create: () => response.promise })
  flow.page.openModal()
  const first = flow.page.createTask(), second = flow.page.createTask()
  try {
    assert.equal(flow.page.creatingTask?.value, true)
    assert.equal(flow.createRequests.length, 1)
  } finally {
    response.resolve({ data: { code: 0, data: { output: { taskId: 'task-1' } } } })
    await Promise.all([first, second])
    flow.page.closeModal()
  }
})

test('outpainting creation HTTP failures show an error and allow retry', async () => {
  const flow = createOutPainting({ create: async () => { throw new Error('HTTP 500') } })
  flow.page.openModal()
  await flow.page.createTask()
  assert.equal(flow.errors.length, 1)
  assert.equal(flow.page.creatingTask.value, false)
  assert.equal(flow.page.taskId.value, undefined)
  await flow.page.createTask()
  assert.equal(flow.createRequests.length, 2)
})

for (const [label, result] of [
  ['business failure', { code: 50000, message: '任务不存在' }],
  ['canceled task', { code: 0, data: { output: { taskStatus: 'CANCELED' } } }],
  ['unknown task', { code: 0, data: { output: { taskStatus: 'UNKNOWN' } } }],
  ['failed task', { code: 0, data: { output: { taskStatus: 'FAILED' } } }],
  ['missing result URL', { code: 0, data: { output: { taskStatus: 'SUCCEEDED' } } }],
]) {
  test(`outpainting stops polling and reports ${label}`, async () => {
    const flow = createOutPainting({ poll: async () => ({ data: result }) })
    flow.page.openModal()
    await flow.page.createTask()
    await flow.tick()
    assert.equal(flow.errors.length, 1)
    assert.equal(flow.timers.size, 0)
    assert.equal(flow.page.taskId.value, undefined)
    assert.equal(flow.page.resultImageUrl.value, undefined)
  })
}

test('outpainting does not overlap slow polling requests', async () => {
  const response = deferred()
  const flow = createOutPainting({ poll: () => response.promise })
  flow.page.openModal()
  await flow.page.createTask()
  const first = flow.tick(), second = flow.tick()
  try {
    assert.equal(flow.pollRequests.length, 1)
  } finally {
    response.resolve({ data: { code: 0, data: { output: { taskStatus: 'RUNNING' } } } })
    await Promise.all([first, second])
    flow.page.closeModal()
  }
})

for (const closeMethod of ['closeModal', 'unmount']) {
  test(`outpainting ignores a late creation response after ${closeMethod}`, async () => {
    const response = deferred()
    const flow = createOutPainting({ create: () => response.promise })
    flow.page.openModal()
    const pending = flow.page.createTask()
    if (closeMethod === 'unmount') flow.unmount()
    else flow.page.closeModal()
    response.resolve({ data: { code: 0, data: { output: { taskId: 'stale-task' } } } })
    await pending
    assert.equal(flow.timers.size, 0)
    assert.equal(flow.page.taskId.value, undefined)
    assert.equal(flow.page.creatingTask.value, false)
    assert.deepEqual(flow.successes, [])
  })

  test(`outpainting clears polling and ignores an in-flight result after ${closeMethod}`, async () => {
    const response = deferred()
    const flow = createOutPainting({ poll: () => response.promise })
    flow.page.openModal()
    await flow.page.createTask()
    const pending = flow.tick()
    const successCount = flow.successes.length
    if (closeMethod === 'unmount') flow.unmount()
    else flow.page.closeModal()
    response.resolve({ data: { code: 0, data: { output: { taskStatus: 'SUCCEEDED', outputImageUrl: '/stale.png' } } } })
    await pending
    assert.equal(flow.timers.size, 0)
    assert.equal(flow.page.resultImageUrl.value, undefined)
    assert.equal(flow.successes.length, successCount)
  })
}

test('outpainting ignores an older task response after the dialog is reopened', async () => {
  const oldResponse = deferred()
  let attempts = 0
  const flow = createOutPainting({ create: () => ++attempts === 1 ? oldResponse.promise : Promise.resolve({ data: { code: 0, data: { output: { taskId: 'new-task' } } } }) })
  flow.page.openModal()
  const oldPending = flow.page.createTask()
  flow.page.closeModal()
  flow.page.openModal()
  await flow.page.createTask()
  oldResponse.resolve({ data: { code: 0, data: { output: { taskId: 'old-task' } } } })
  await oldPending
  assert.equal(flow.page.taskId.value, 'new-task')
  assert.equal(flow.timers.size, 1)
  flow.page.closeModal()
})

test('outpainting applies a successful output URL once and notifies the parent', async () => {
  const response = deferred()
  const flow = createOutPainting({
    poll: async () => ({ data: { code: 0, data: { output: { taskStatus: 'SUCCEEDED', outputImageUrl: '/expanded.png' } } } }),
    upload: () => response.promise,
  })
  flow.page.openModal()
  await flow.page.createTask()
  await flow.tick()
  assert.equal(flow.page.resultImageUrl.value, '/expanded.png')
  assert.equal(flow.timers.size, 0)
  const first = flow.page.handleUpload(), second = flow.page.handleUpload()
  try {
    assert.equal(flow.uploadRequests.length, 1)
    assert.deepEqual(flow.uploadRequests[0], { fileUrl: '/expanded.png', id: '7', spaceId: '12' })
  } finally {
    response.resolve({ data: { code: 0, data: { id: '7', url: '/expanded.png' } } })
    await Promise.all([first, second])
  }
  assert.deepEqual(flow.applied, [{ id: '7', url: '/expanded.png' }])
  assert.equal(flow.page.visible.value, false)
})

test('outpainting ignores an apply response after the dialog is closed', async () => {
  const response = deferred()
  const flow = createOutPainting({ upload: () => response.promise })
  flow.page.openModal()
  flow.page.resultImageUrl.value = '/expanded.png'
  const pending = flow.page.handleUpload()
  flow.page.closeModal()
  response.resolve({ data: { code: 0, data: { id: '7', url: '/expanded.png' } } })
  await pending
  assert.deepEqual(flow.applied, [])
  assert.equal(flow.page.uploadLoading.value, false)
})
