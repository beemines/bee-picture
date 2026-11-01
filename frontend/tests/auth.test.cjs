/* eslint-disable @typescript-eslint/no-require-imports -- This Node harness loads transpiled CommonJS modules and the same Pinia instance. */
const assert = require('node:assert/strict')
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const { test } = require('node:test')
const ts = require('typescript')
const { parse, compileScript } = require('@vue/compiler-sfc')
const { createPinia, setActivePinia } = require('pinia')

// Run the real TypeScript and SFC setup code with only HTTP/UI boundaries replaced.
// This keeps the tests dependency-free beyond the frontend's existing toolchain.
function loadSource(relativePath, replacements = {}) {
  const filename = path.resolve(__dirname, '..', path.extname(relativePath) ? relativePath : relativePath + '.ts')
  let source = readFileSync(filename, 'utf8')
  if (filename.endsWith('.vue')) {
    const { descriptor } = parse(source, { filename })
    source = compileScript(descriptor, { id: 'auth-regression' }).content
  }
  const compiled = ts.transpileModule(source, {
    fileName: filename,
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
    transformers: {
      before: [(context) => (node) => ts.visitNode(node, function visit(current) {
        if (ts.isMetaProperty(current) && current.keywordToken === ts.SyntaxKind.ImportKeyword) {
          return context.factory.createIdentifier('__importMeta')
        }
        return ts.visitEachChild(current, visit, context)
      })],
    },
  }).outputText
  const nativeRequire = createRequire(filename)
  const module = { exports: {} }
  const requireSource = (name) => {
    if (Object.hasOwn(replacements, name)) return replacements[name]
    if (name.startsWith('@/')) return loadSource('src/' + name.slice(2), replacements)
    return nativeRequire(name)
  }
  new Function('require', 'module', 'exports', '__importMeta', compiled)(requireSource, module, module.exports, { env: {} })
  return module.exports
}

function createStore(request) {
  setActivePinia(createPinia())
  const { useLoginUserStore } = loadSource('src/stores/useLoginUserStore.ts', {
    '@/api/userController.ts': { getLoginUserUsingGet: request },
  })
  return useLoginUserStore()
}

function createGuard(store) {
  let guard
  const errors = []
  loadSource('src/access.ts', {
    '@/stores/useLoginUserStore': { useLoginUserStore: () => store },
    'ant-design-vue': { message: { error: (value) => errors.push(value), warning: (value) => errors.push(value) } },
    '@/router': { beforeEach: (callback) => { guard = callback } },
  })
  return { guard, errors }
}

function createLoginPage({ request, redirect, matched = true }) {
  const errors = []
  const destinations = []
  const store = createStore(async () => { throw new Error('identity refresh unavailable') })
  const router = {
    push: (value) => { destinations.push(value) },
    replace: async (value) => { destinations.push(value) },
    resolve: () => ({ matched: matched ? [{}] : [] }),
  }
  const component = loadSource('src/pages/user/UserLoginPage.vue', {
    'vue-router': { useRouter: () => router, useRoute: () => ({ query: { redirect } }) },
    '@/stores/useLoginUserStore.ts': { useLoginUserStore: () => store },
    '@/api/userController.ts': { userLoginUsingPost: request },
    'ant-design-vue': { message: { success() {}, error: (value) => errors.push(value) } },
  }).default
  const page = component.setup({}, { expose() {} })
  return { page, store, errors, destinations }
}

test('a failed identity refresh clears stale administrator state and resolves as unavailable', async () => {
  const store = createStore(async () => { throw new Error('HTTP 500') })
  store.setLoginUser({ id: '1', userName: 'Admin', userRole: 'admin' })
  assert.equal(await store.fetchLoginUser(), false)
  assert.equal(store.loginUser.id, undefined)
  assert.equal(store.loginUser.userRole, undefined)
})

test('an expired session clears the identity without classifying logout as a service failure', async () => {
  const store = createStore(async () => ({ data: { code: 40100, message: '未登录' } }))
  store.setLoginUser({ id: '1', userRole: 'admin' })
  assert.equal(await store.fetchLoginUser(), true)
  assert.equal(store.loginUser.id, undefined)
})

test('an identity API business failure cannot preserve cached authentication', async () => {
  const store = createStore(async () => ({ data: { code: 50000, message: '系统错误' } }))
  store.setLoginUser({ id: '1', userRole: 'admin' })
  assert.equal(await store.fetchLoginUser(), false)
  assert.equal(store.loginUser.id, undefined)
})

test('a successful identity refresh installs the server identity', async () => {
  const store = createStore(async () => ({ data: { code: 0, data: { id: '7', userRole: 'user' } } }))
  assert.equal(await store.fetchLoginUser(), true)
  assert.equal(store.loginUser.id, '7')
})

test('public navigation continues after an initial HTTP failure and retries on the next navigation', async () => {
  let requests = 0
  const store = createStore(async () => {
    if (++requests === 1) throw new Error('HTTP 500')
    return { data: { code: 0, data: { id: '7', userRole: 'admin' } } }
  })
  const { guard, errors } = createGuard(store)
  const nextCalls = []
  await guard({ fullPath: '/' }, {}, (value) => nextCalls.push(value))
  assert.deepEqual(nextCalls, [undefined])
  assert.equal(errors.length, 1)
  await guard({ fullPath: '/admin/userManage' }, {}, (value) => nextCalls.push(value))
  assert.deepEqual(nextCalls, [undefined, undefined])
  assert.equal(store.loginUser.id, '7')
})

test('unavailable authentication still prevents opening administrator routes', async () => {
  const store = createStore(async () => { throw new Error('network unavailable') })
  const { guard } = createGuard(store)
  const nextCalls = []
  await guard({ fullPath: '/admin/userManage?name=A&B' }, {}, (value) => nextCalls.push(value))
  assert.deepEqual(nextCalls, [{ path: '/user/login', query: { redirect: '/admin/userManage?name=A&B' } }])
})

test('login after a failed bootstrap is not overwritten by another unavailable identity request', async () => {
  let requests = 0
  const store = createStore(async () => { requests++; throw new Error('HTTP 500') })
  const { guard } = createGuard(store)
  await guard({ fullPath: '/user/login' }, {}, () => {})
  store.setLoginUser({ id: '7', userRole: 'admin' })
  const nextCalls = []
  await guard({ fullPath: '/admin/userManage' }, {}, (value) => nextCalls.push(value))
  assert.equal(store.loginUser.id, '7')
  assert.equal(requests, 1)
  assert.deepEqual(nextCalls, [undefined])
})

test('an authenticated non-administrator is sent home instead of back to login', async () => {
  const store = createStore(async () => { throw new Error('No extra identity request expected') })
  store.setLoginUser({ id: '7', userRole: 'user' })
  const { guard, errors } = createGuard(store)
  const nextCalls = []
  await guard({ fullPath: '/admin/userManage' }, {}, (value) => nextCalls.push(value))
  assert.deepEqual(nextCalls, ['/'])
  assert.equal(errors.length, 1)
  assert.equal(store.loginUser.id, '7')
})

test('an interceptor login return preserves every query argument and hash', async () => {
  const originalWindow = globalThis.window
  const origin = 'http://localhost:5175'
  const fullPath = '/space/7?name=Blue%20sky&sort=desc#details'
  globalThis.window = {
    location: {
      origin, pathname: '/space/7', search: '?name=Blue%20sky&sort=desc', hash: '#details',
      href: origin + fullPath,
    },
  }
  try {
    let interceptResponse
    loadSource('src/request.ts', {
      axios: { create: () => ({ interceptors: {
        request: { use() {} },
        response: { use: (callback) => { interceptResponse = callback } },
      } }) },
      'ant-design-vue': { message: { warning() {} } },
    })
    interceptResponse({
      data: { code: 40100, message: '未登录' },
      request: { responseURL: origin + '/api/space/get/vo?id=7' },
    })
    const loginUrl = new URL(globalThis.window.location.href, origin)
    assert.equal(loginUrl.pathname, '/user/login')
    assert.equal(loginUrl.searchParams.get('redirect'), fullPath)
    assert.equal(loginUrl.searchParams.has('sort'), false)
    assert.equal(loginUrl.hash, '')
    const { page, destinations } = createLoginPage({
      redirect: loginUrl.searchParams.get('redirect'),
      request: async () => ({ data: { code: 0, data: { id: '7' } } }),
    })
    await page.handleSubmit({ userAccount: 'member', userPassword: 'password123' })
    assert.deepEqual(destinations, [fullPath])
  } finally {
    globalThis.window = originalWindow
  }
})

test('successful login uses the returned identity and restores the requested application route', async () => {
  const originalWindow = globalThis.window
  globalThis.window = { location: { origin: 'http://localhost:5175' } }
  try {
    const { page, store, destinations } = createLoginPage({
      redirect: '/space/7?tab=members#details',
      request: async () => ({ data: { code: 0, data: { id: '7', userRole: 'user' } } }),
    })
    await page.handleSubmit({ userAccount: 'member', userPassword: 'password123' })
    assert.equal(store.loginUser.id, '7')
    assert.deepEqual(destinations, ['/space/7?tab=members#details'])
  } finally {
    globalThis.window = originalWindow
  }
})

test('a rejected login request gives usable feedback and leaves the form retryable', async () => {
  const { page, store, errors, destinations } = createLoginPage({ request: async () => { throw new Error('HTTP 500') } })
  await page.handleSubmit({ userAccount: 'member', userPassword: 'password123' })
  assert.equal(store.loginUser.id, undefined)
  assert.equal(errors.length, 1)
  assert.equal(page.submitting.value, false)
  assert.deepEqual(destinations, [])
})

test('an unknown redirect route returns a signed-in user to the home page', async () => {
  const originalWindow = globalThis.window
  globalThis.window = { location: { origin: 'http://localhost:5175' } }
  try {
    const { page, destinations } = createLoginPage({
      redirect: '/missing-page', matched: false,
      request: async () => ({ data: { code: 0, data: { id: '7' } } }),
    })
    await page.handleSubmit({ userAccount: 'member', userPassword: 'password123' })
    assert.deepEqual(destinations, ['/'])
  } finally {
    globalThis.window = originalWindow
  }
})

for (const [redirect, destination] of [
  ['http://localhost:5175/space/7?tab=members#details', '/space/7?tab=members#details'],
  ['/space/7?next=https%3A%2F%2Fexample.com', '/space/7?next=https%3A%2F%2Fexample.com'],
  ['https://evil.example/space/7', '/'],
  ['http://localhost:5176/space/7', '/'],
  ['http://localhost:5175@evil.example/space/7', '/'],
  ['http://user:password@localhost:5175/space/7', '/'],
  ['//evil.example/space/7', '/'],
  ['/a/..//evil.example', '/'],
  ['http://localhost:5175//evil.example', '/'],
  ['/\\evil.example/space/7', '/'],
  ['/%5cevil.example/space/7', '/'],
  ['javascript:alert(1)', '/'],
  ['data:text/html,malicious', '/'],
  [' /space/7', '/'],
  ['/space/7\n', '/'],
  ['/user/login?redirect=/space/7', '/'],
  [['/space/7', '//evil.example'], '/'],
  [undefined, '/'],
]) {
  test(`login safely normalizes redirect ${JSON.stringify(redirect)}`, async () => {
    const originalWindow = globalThis.window
    globalThis.window = { location: { origin: 'http://localhost:5175' } }
    try {
      const { page, destinations } = createLoginPage({
        redirect,
        request: async () => ({ data: { code: 0, data: { id: '7' } } }),
      })
      await page.handleSubmit({ userAccount: 'member', userPassword: 'password123' })
      assert.deepEqual(destinations, [destination])
    } finally {
      globalThis.window = originalWindow
    }
  })
}
