/* eslint-disable @typescript-eslint/no-require-imports -- Node harness exercises actual SFC setup code. */
const assert = require('node:assert/strict')
const { test } = require('node:test')
const vue = require('vue')
const { loadSource } = require('./helpers/load-source.cjs')

const filterFixture = {
  searchText: 'QA私人', name: 'Blue sky', introduction: 'Unsaved query', category: '风景',
  tags: ['QA'], startEditTime: new Date('2026-10-01T00:00:00Z'),
  endEditTime: new Date('2026-10-31T00:00:00Z'), picWidth: 1280, picHeight: 720, picFormat: 'png',
}
const defaultFormQuery = { current: 1, pageSize: 24, sortField: 'createTime', sortOrder: 'descend' }
const defaultSpaceQuery = { current: 1, pageSize: 12, sortField: 'createTime', sortOrder: 'descend' }

function deferred() {
  let resolve, reject
  const promise = new Promise((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}

function createSearchForm(onSearch) {
  return loadSource('src/components/PictureSearchForm.vue', {
    vue: { ...vue, onMounted() {} },
    'ant-design-vue': { message: { error() {} } },
    '@/api/pictureController.ts': {},
  }).default.setup({ onSearch }, { expose() {} })
}

function createSpacePage(t, { list, detail, color } = {}) {
  const errors = [], listRequests = [], detailRequests = []
  const props = vue.reactive({ id: '9007199254740993123' })
  const scope = vue.effectScope()
  t.after(() => scope.stop())
  const component = loadSource('src/pages/user/SpaceDetailPage.vue', {
    vue: { ...vue, onMounted() {} },
    '@/api/spaceController.ts': { getSpaceVoByIdUsingGet: (params) => {
      detailRequests.push(params)
      return detail ? detail(params) : Promise.resolve({ data: { code: 0, data: { id: params.id, spaceName: 'Current space' } } })
    } },
    '@/api/pictureController.ts': {
      listPictureVoByPageUsingPost: (params) => {
        listRequests.push(params)
        return list ? list(params) : Promise.resolve({ data: { code: 0, data: { records: [], total: '0' } } })
      },
      searchPictureByColorUsingPost: (params) => color(params),
    },
    'ant-design-vue': { message: { error: (text) => errors.push(text) } },
    '@/utils': { formatSize() {} },
    '@/components/PictureList.vue': {}, '@/components/PictureSearchForm.vue': {},
    '@/components/BatchEditPictureModal.vue': {},
    'vue3-colorpicker': {}, 'vue3-colorpicker/style.css': {}, '@ant-design/icons-vue': {},
  }).default
  const page = scope.run(() => component.setup(props, { expose() {} }))
  return { page, props, errors, listRequests, detailRequests }
}

test('search starts at page one and emits every active filter as a snapshot', () => {
  const searches = []
  const form = createSearchForm((query) => searches.push(query))
  Object.assign(form.searchParams, filterFixture, { current: 8 })
  form.doSearch()
  assert.deepEqual(searches[0], { ...defaultFormQuery, ...filterFixture })
  form.searchParams.name = 'Changed after submit'
  assert.equal(searches[0].name, 'Blue sky')
})

test('reset removes all prior filters from the actual space request', async (t) => {
  const { page, props, listRequests } = createSpacePage(t)
  const form = createSearchForm(page.onSearch)
  Object.assign(form.searchParams, filterFixture)
  form.dateRange.value = ['visible start', 'visible end']
  form.doSearch()
  page.searchParams.value.current = 6
  form.doClear()
  assert.deepEqual(listRequests.at(-1), { ...defaultFormQuery, spaceId: props.id })
  assert.deepEqual({ ...page.searchParams.value }, defaultFormQuery)
  assert.deepEqual({ ...form.searchParams }, defaultFormQuery)
  assert.deepEqual(form.dateRange.value, [])
  await Promise.resolve()
})

test('reset does not hand mutable form state to its consumer', () => {
  const searches = []
  const form = createSearchForm((query) => searches.push(query))
  Object.assign(form.searchParams, filterFixture)
  form.doClear()
  form.searchParams.name = 'Next search'
  assert.deepEqual(searches[0], defaultFormQuery)
})

test('a search cannot replace the current space scope with a stale filter value', async (t) => {
  const { page, props, listRequests } = createSpacePage(t)
  page.onSearch({ spaceId: 'different-space', searchText: 'Scoped query' })
  assert.equal(listRequests.at(-1).spaceId, props.id)
  await Promise.resolve()
})

test('space switching clears prior filters, permissions and results and rejects late responses', async (t) => {
  const oldList = deferred(), newList = deferred(), oldDetail = deferred(), newDetail = deferred()
  const oldId = '9007199254740993123', newId = '9007199254740993125'
  const { page, props, listRequests } = createSpacePage(t, {
    list: ({ spaceId }) => spaceId === oldId ? oldList.promise : newList.promise,
    detail: ({ id }) => id === oldId ? oldDetail.promise : newDetail.promise,
  })
  page.space.value = { id: oldId, permissionList: ['picture:edit'] }
  page.dataList.value = [{ id: 'old-picture' }]
  page.total.value = 3
  page.searchParams.value = { ...defaultSpaceQuery, ...filterFixture, current: 6 }
  const oldListPending = page.fetchData(), oldDetailPending = page.fetchSpaceDetail()
  props.id = newId
  await vue.nextTick()
  assert.deepEqual(page.dataList.value, [])
  assert.equal(page.total.value, 0)
  assert.deepEqual(page.space.value, {})
  assert.deepEqual(page.searchParams.value, defaultSpaceQuery)
  assert.deepEqual(listRequests.at(-1), { ...defaultSpaceQuery, spaceId: newId })
  newDetail.resolve({ data: { code: 0, data: { id: newId, permissionList: ['picture:view'] } } })
  newList.resolve({ data: { code: 0, data: { records: [{ id: 'new-picture' }], total: '1' } } })
  await Promise.resolve()
  oldDetail.resolve({ data: { code: 0, data: { id: oldId, permissionList: ['picture:edit'] } } })
  oldList.resolve({ data: { code: 0, data: { records: [{ id: 'old-picture' }], total: '3' } } })
  await Promise.all([oldListPending, oldDetailPending])
  assert.equal(page.space.value.id, newId)
  assert.deepEqual(page.dataList.value, [{ id: 'new-picture' }])
  assert.equal(page.total.value, 1)
  assert.equal(page.loading.value, false)
})

test('a color response from the old space cannot overwrite the newly loaded space', async (t) => {
  const oldColor = deferred()
  const { page, props } = createSpacePage(t, {
    color: () => oldColor.promise,
    list: async () => ({ data: { code: 0, data: { records: [{ id: 'new-picture' }], total: '1' } } }),
  })
  const pending = page.onColorChange('#112233')
  props.id = 'new-space'
  await vue.nextTick()
  oldColor.resolve({ data: { code: 0, data: [{ id: 'stale-color-result' }] } })
  await pending
  assert.deepEqual(page.dataList.value, [{ id: 'new-picture' }])
  assert.equal(page.total.value, 1)
})

test('a slower filtered request cannot overwrite results returned by reset', async (t) => {
  const filtered = deferred(), reset = deferred()
  const { page } = createSpacePage(t, { list: ({ searchText }) => searchText ? filtered.promise : reset.promise })
  page.onSearch({ searchText: 'QA私人' })
  page.onSearch({ ...defaultFormQuery })
  reset.resolve({ data: { code: 0, data: { records: [{ id: 'all-results' }], total: '5' } } })
  await Promise.resolve()
  filtered.resolve({ data: { code: 0, data: { records: [{ id: 'filtered-results' }], total: '3' } } })
  await Promise.resolve()
  assert.equal(page.total.value, 5)
  assert.deepEqual(page.dataList.value, [{ id: 'all-results' }])
})

for (const [filename, apiModule, apiName] of [
  ['src/pages/user/HomePage.vue', '@/api/pictureController.ts', 'listPictureVoByPageUsingPost'],
  ['src/pages/admin/PictureManagePage.vue', '@/api/pictureController.ts', 'listPictureByPageUsingPost'],
  ['src/pages/admin/SpaceManagePage.vue', '@/api/spaceController.ts', 'listSpaceByPageUsingPost'],
  ['src/pages/admin/UserManagePage.vue', '@/api/userController.ts', 'listUserVoByPageUsingPost'],
]) {
  test(`${filename} converts pagination totals without changing large string IDs`, async () => {
    const record = { id: '9007199254740993123', userId: '9007199254740993125' }
    const component = loadSource(filename, {
      vue: { ...vue, onMounted() {} },
      [apiModule]: { [apiName]: async () => ({ data: { code: 0, data: { records: [record], total: '5' } } }) },
      'ant-design-vue': { message: { error() {} } },
      '@/components/PictureList.vue': {}, '@/utils': { formatSize() {} },
    }).default
    const page = component.setup({}, { expose() {} })
    await page.fetchData()
    assert.equal(page.total.value, 5)
    assert.deepEqual(page.dataList.value, [record])
  })
}

test('space pagination converts totals without converting space or picture IDs', async (t) => {
  const record = { id: '9007199254740993127', spaceId: '9007199254740993123' }
  const { page, listRequests } = createSpacePage(t, {
    list: async () => ({ data: { code: 0, data: { records: [record], total: '5' } } }),
  })
  await page.fetchData()
  assert.equal(page.total.value, 5)
  assert.deepEqual(page.dataList.value, [record])
  assert.equal(listRequests[0].spaceId, '9007199254740993123')
})
