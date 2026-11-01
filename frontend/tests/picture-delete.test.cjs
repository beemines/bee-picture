/* eslint-disable @typescript-eslint/no-require-imports -- Node harness exercises actual SFC setup code. */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const vue = require('vue')
const { loadSource } = require('./helpers/load-source.cjs')

function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

function createDetailPage(request, spaceId) {
  const requests = [], errors = [], successes = [], destinations = []
  const component = loadSource('src/pages/user/PictureDetailPage.vue', {
    vue: { ...vue, onMounted() {} },
    '@/api/pictureController.ts': { deletePictureUsingPost: (body) => { requests.push(body); return request(body) } },
    'ant-design-vue': { message: { error: (text) => errors.push(text), success: (text) => successes.push(text) } },
    'vue-router': { useRouter: () => ({ replace: async (destination) => { destinations.push(destination) } }) },
    '@ant-design/icons-vue': {}, '@/utils': {}, '@/components/ShareModal.vue': {},
  }).default
  const page = component.setup({ id: '9007199254740993123' }, { expose() {} })
  page.picture.value = { id: '9007199254740993123', spaceId, name: 'QA fixture', introduction: 'Keep this on failure' }
  return { page, requests, errors, successes, destinations }
}

for (const [spaceId, destination] of [['9007199254740993125', '/space/9007199254740993125'], [undefined, '/']]) {
  test(`successful picture deletion replaces the deleted detail with ${destination}`, async () => {
    const flow = createDetailPage(async () => ({ data: { code: 0, data: true } }), spaceId)
    await flow.page.doDelete()
    assert.deepEqual(flow.requests, [{ id: '9007199254740993123' }])
    assert.deepEqual(flow.destinations, [destination])
    assert.deepEqual(flow.successes, ['删除成功'])
    assert.deepEqual(flow.errors, [])
  })
}

test('picture deletion becomes pending immediately and ignores duplicate clicks', async () => {
  const response = deferred()
  const flow = createDetailPage(() => response.promise, '9007199254740993125')
  const first = flow.page.doDelete(), second = flow.page.doDelete()
  try {
    assert.equal(flow.page.deleting?.value, true)
    assert.equal(flow.requests.length, 1)
  } finally {
    response.resolve({ data: { code: 0, data: true } })
    await Promise.all([first, second])
  }
  assert.equal(flow.page.deleting.value, false)
  assert.deepEqual(flow.destinations, ['/space/9007199254740993125'])
})

test('picture deletion HTTP failure preserves the page and allows a later retry', async () => {
  let attempts = 0
  const flow = createDetailPage(async () => {
    if (++attempts === 1) throw new Error('HTTP 500')
    return { data: { code: 0, data: true } }
  }, '9007199254740993125')
  const before = { ...flow.page.picture.value }
  await flow.page.doDelete()
  assert.equal(flow.page.deleting.value, false)
  assert.equal(flow.errors.length, 1)
  assert.match(flow.errors[0], /删除失败.*稍后重试/)
  assert.deepEqual(flow.page.picture.value, before)
  assert.deepEqual(flow.destinations, [])
  assert.deepEqual(flow.successes, [])
  await flow.page.doDelete()
  assert.equal(flow.requests.length, 2)
  assert.deepEqual(flow.destinations, ['/space/9007199254740993125'])
})

test('picture deletion business failure preserves the record and displays the server reason', async () => {
  const flow = createDetailPage(async () => ({ data: { code: 40101, message: '没有权限' } }), undefined)
  const before = { ...flow.page.picture.value }
  await flow.page.doDelete()
  assert.equal(flow.page.deleting?.value, false)
  assert.deepEqual(flow.errors, ['删除失败，没有权限'])
  assert.deepEqual(flow.page.picture.value, before)
  assert.deepEqual(flow.destinations, [])
  assert.deepEqual(flow.successes, [])
})

test('a failed logout reports failure while preserving the authenticated user', async () => {
  const errors = [], successes = [], destinations = [], updates = []
  const store = { loginUser: { id: '7', userName: 'Member' }, setLoginUser: (user) => updates.push(user) }
  const component = loadSource('src/components/GlobalHeader.vue', {
    'ant-design-vue': { message: { error: (text) => errors.push(text), success: (text) => successes.push(text) } },
    'vue-router': { useRouter: () => ({ afterEach() {}, push: (destination) => destinations.push(destination) }) },
    '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => store },
    '@/api/userController.ts': { userLogoutUsingPost: async () => ({ data: { code: 50000, message: '服务器拒绝请求' } }) },
    '@ant-design/icons-vue': {},
  }, { console: { ...console, log() {} } }).default
  const page = component.setup({}, { expose() {} })
  await page.doLogout()
  assert.deepEqual(errors, ['退出登录失败，服务器拒绝请求'])
  assert.deepEqual(successes, [])
  assert.deepEqual(destinations, [])
  assert.deepEqual(updates, [])
  assert.equal(store.loginUser.id, '7')
})
