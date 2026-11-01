/* eslint-disable @typescript-eslint/no-require-imports -- Exercises the real compiled SFC setup code. */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const vue = require('vue')
const { loadSource } = require('./helpers/load-source.cjs')

function deferred() {
  let resolve
  const promise = new Promise((done) => { resolve = done })
  return { promise, resolve }
}

function createPage(request = async () => ({ data: { code: 0, data: '120' } })) {
  const errors = [], successes = [], additions = [], updates = []
  let refreshes = 0
  const component = loadSource('src/pages/admin/UserManagePage.vue', {
    vue: { ...vue, onMounted() {} },
    'ant-design-vue': { message: { error: (text) => errors.push(text), success: (text) => successes.push(text) } },
    '@/api/userController.ts': {
      addUserUsingPost: (body) => { additions.push(body); return request(body) },
      updateUserUsingPost: (body) => { updates.push(body); return request(body) },
      listUserVoByPageUsingPost: async () => { refreshes++; return { data: { code: 0, data: { records: [], total: 0 } } } },
    },
  }).default
  const page = component.setup({}, { expose() {} })
  page.doAdd()
  return { page, errors, successes, additions, updates, refreshes: () => refreshes }
}

for (const account of ['', ' ', '\t\n', 'a', 'abc', 'a'.repeat(257)]) {
  test(`invalid account ${JSON.stringify(account.length > 20 ? '257 characters' : account)} stays in the add form without sending a request`, async () => {
    const flow = createPage()
    flow.page.formState.userAccount = account
    await flow.page.handleModalOk()
    assert.deepEqual(flow.additions, [])
    assert.equal(flow.page.open.value, true)
    assert.match(flow.errors[0], /账号/)
    assert.deepEqual(flow.successes, [])
  })
}

for (const edit of [false, true]) {
  const action = edit ? '编辑' : '添加'
  test(`${action} pending state immediately blocks duplicate submissions`, async () => {
    const response = deferred()
    const flow = createPage(() => response.promise)
    if (edit) flow.page.doUpdate({ id: '120', userAccount: 'member', userRole: 'user' })
    else flow.page.formState.userAccount = 'member'
    const first = flow.page.handleModalOk(), second = flow.page.handleModalOk()
    try {
      assert.equal(flow.additions.length + flow.updates.length, 1)
      assert.equal(flow.page.submitting?.value, true)
    } finally {
      response.resolve({ data: { code: 0, data: edit ? true : '120' } })
      await Promise.all([first, second])
    }
    assert.equal(flow.page.submitting.value, false)
    assert.equal(flow.page.open.value, false)
    assert.deepEqual(flow.successes, [`${action}成功`])
    assert.equal(flow.refreshes(), 1)
  })

  test(`${action} HTTP failures keep entered data, show feedback and allow retry`, async () => {
    const flow = createPage(async () => { throw new Error('HTTP 500') })
    if (edit) flow.page.doUpdate({ id: '120', userAccount: 'member', userRole: 'user' })
    else flow.page.formState.userAccount = 'member'
    flow.page.formState.userName = 'unsaved name'
    await flow.page.handleModalOk()
    assert.equal(flow.page.open.value, true)
    assert.equal(flow.page.formState.userName, 'unsaved name')
    assert.equal(flow.page.submitting.value, false)
    assert.match(flow.errors[0], new RegExp(`${action}失败`))
    assert.deepEqual(flow.successes, [])
    assert.equal(flow.refreshes(), 0)
    await flow.page.handleModalOk()
    assert.equal(flow.additions.length + flow.updates.length, 2)
  })

  test(`${action} business failures keep the form open and display the server reason`, async () => {
    const flow = createPage(async () => ({ data: { code: 40000, message: '账号已存在' } }))
    if (edit) flow.page.doUpdate({ id: '120', userAccount: 'member', userRole: 'user' })
    else flow.page.formState.userAccount = 'member'
    await flow.page.handleModalOk()
    assert.equal(flow.page.open.value, true)
    assert.equal(flow.page.submitting?.value, false)
    assert.match(flow.errors[0], new RegExp(`${action}失败.*账号已存在`))
    assert.deepEqual(flow.successes, [])
  })
}

test('valid registration-compatible punctuation and Unicode accounts are sent unchanged', async () => {
  const flow = createPage()
  flow.page.formState.userAccount = '账+@!'
  await flow.page.handleModalOk()
  assert.equal(flow.additions[0].userAccount, '账+@!')
  assert.equal(flow.additions[0].userRole, 'user')
  assert.deepEqual(flow.successes, ['添加成功'])
})
