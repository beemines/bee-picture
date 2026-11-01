/**
 * Read-only documentation fixtures. Never connects to MySQL, Redis or a cloud API.
 * All accounts, IDs, statistics and SVG illustrations are synthetic.
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const directory = path.dirname(fileURLToPath(import.meta.url))
const port = Number(process.env.DEMO_PORT || 8130)
const origin = `http://127.0.0.1:${port}`
const permissions = ['spaceUser:manage', 'picture:view', 'picture:upload', 'picture:edit', 'picture:delete']
const user = { id: 10001, userAccount: 'demo_admin', userName: '演示管理员', userRole: 'admin',
  userAvatar: `${origin}/demo-assets/logo.svg`, userProfile: '文档演示账号', createTime: '2026-01-01T09:00:00' }
const space = { id: 20001, spaceName: '设计协作素材库', spaceType: 1, spaceLevel: 1, userId: 10001,
  maxSize: 10737418240, totalSize: 2147483648, maxCount: 1000, totalCount: 126,
  permissionList: permissions, user, createTime: '2026-01-10T09:00:00' }
const names = ['山野来信', '沙丘日落', '海岸留白', '森林呼吸', '城市一角', '月光湖面',
  '远山回响', '岛屿假日', '几何花园', '午后建筑', '晴空之下', '向海而行']
const pictures = names.map((name, i) => ({ id: 30001 + i, name,
  introduction: 'Bee Picture 文档演示插画，由简单几何图形绘制，可随代码自由使用',
  url: `${origin}/demo-assets/art-${i + 1}.svg`, thumbnailUrl: `${origin}/demo-assets/art-${i + 1}.svg`,
  category: ['素材', '背景', '海报'][i % 3], tags: [['自然', '高清'], ['设计', '配色'], ['艺术', '创意']][i % 3],
  picSize: 524288 + i * 32768, picWidth: 1200, picHeight: 800, picScale: 1.5, picFormat: 'SVG',
  picColor: '0x' + ['7B9679', 'D5AD7C', '8DB4BB'][i % 3], userId: user.id, user,
  createTime: '2026-02-10T10:00:00', editTime: '2026-02-10T11:00:00', spaceId: 20001,
  reviewStatus: i === 0 ? 0 : 1, reviewMessage: i === 0 ? '等待审核' : '演示审核通过',
  reviewerId: 10001, permissionList: permissions }))
const members = ['演示管理员', '林川', '陈夏'].map((name, i) => ({ id: 40001 + i, spaceId: 20001,
  userId: 10001 + i, spaceRole: ['admin', 'editor', 'viewer'][i], space,
  createTime: '2026-02-01T09:00:00', user: { ...user, id: 10001 + i, userName: name } }))
const ok = (data) => ({ code: 0, data, message: 'ok' })

function result(url, body) {
  const endpoint = url.pathname
  if (endpoint === '/api/user/get/login') return user
  if (endpoint === '/api/picture/tag_category') return { categoryList: ['素材', '背景', '海报'], tagList: ['自然', '高清', '设计', '配色', '艺术', '创意'] }
  if (endpoint === '/api/picture/get/vo' || endpoint === '/api/picture/get') return pictures.find(p => p.id === Number(url.searchParams.get('id'))) || pictures[0]
  if (endpoint.startsWith('/api/picture/list/page')) {
    let filtered = pictures.filter(p => !body.searchText || p.name.includes(body.searchText))
    if (body.category) filtered = filtered.filter(p => p.category === body.category)
    if (body.tags?.length) filtered = filtered.filter(p => body.tags.every(t => p.tags.includes(t)))
    if (endpoint === '/api/picture/list/page') filtered = filtered.map(p => ({ ...p, tags: JSON.stringify(p.tags) }))
    const pageSize = Math.min(Number(body.pageSize) || 12, 24)
    return { records: filtered.slice(0, pageSize), total: filtered.length, current: 1, size: pageSize }
  }
  if (endpoint === '/api/space/get/vo' || endpoint === '/api/space/get') return space
  if (endpoint.startsWith('/api/space/list/page')) return { records: [space], total: 1, current: 1, size: 10 }
  if (endpoint === '/api/space/list/level') return [{ text: '普通版', value: 0, maxCount: 100, maxSize: 104857600 }, { text: '专业版', value: 1, maxCount: 1000, maxSize: 10737418240 }]
  if (endpoint === '/api/spaceUser/list/my') return [members[0]]
  if (endpoint === '/api/spaceUser/list') return members
  if (endpoint === '/api/user/list/page/vo' || endpoint === '/api/user/list/page') return { records: members.map(m => m.user), total: 3, current: 1, size: 10 }
  if (endpoint === '/api/space/analyze/usage') return { usedSize: space.totalSize, maxSize: space.maxSize, sizeUsageRatio: 20, usedCount: 126, maxCount: 1000, countUsageRatio: 12.6 }
  if (endpoint === '/api/space/analyze/category') return ['素材', '背景', '海报'].map((category, i) => ({ category, count: [62, 38, 26][i], totalSize: [835715200, 648576000, 393216000][i] }))
  if (endpoint === '/api/space/analyze/tag') return ['自然', '设计', '高清', '配色', '艺术', '创意'].map((tag, i) => ({ tag, count: [52, 39, 45, 26, 19, 31][i] }))
  if (endpoint === '/api/space/analyze/size') return ['小于 100KB', '100KB–1MB', '1MB–5MB', '大于 5MB'].map((sizeRange, i) => ({ sizeRange, count: [15, 62, 38, 11][i] }))
  if (endpoint === '/api/space/analyze/user') return [1, 2, 3, 4, 5, 6, 7].map((d, i) => ({ period: `2026-02-0${d}`, count: [8, 12, 7, 19, 15, 23, 17][i] }))
  if (endpoint === '/api/space/analyze/rank') return [space, { ...space, spaceName: '产品素材', totalSize: 1280000000 }, { ...space, spaceName: '品牌设计', totalSize: 860000000 }]
  return undefined
}

http.createServer(async (req, res) => {
  const url = new URL(req.url, origin)
  res.setHeader('Access-Control-Allow-Origin', '*')
  res.setHeader('Access-Control-Allow-Headers', 'Content-Type')
  res.setHeader('Cache-Control', 'no-store')
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return }
  if (url.pathname.startsWith('/demo-assets/')) {
    const name = path.basename(url.pathname)
    if (!/^(art-\d+|logo)\.svg$/.test(name)) { res.writeHead(404); res.end(); return }
    const file = path.join(directory, 'assets', name)
    if (!fs.existsSync(file)) { res.writeHead(404); res.end(); return }
    res.setHeader('Content-Type', 'image/svg+xml')
    fs.createReadStream(file).pipe(res)
    return
  }
  let raw = ''
  for await (const chunk of req) { raw += chunk; if (raw.length > 65536) { res.writeHead(413); res.end(); return } }
  let body
  try { body = raw ? JSON.parse(raw) : {} } catch { res.writeHead(400); res.end(); return }
  res.setHeader('Content-Type', 'application/json; charset=utf-8')
  if (url.pathname === '/api/user/get/login' && /\/user\/(login|register)/.test(req.headers.referer || '')) {
    res.end(JSON.stringify({ code: 40100, message: '文档演示登录页，未建立真实会话' }))
    return
  }
  const data = result(url, body)
  if (data === undefined) { res.writeHead(403); res.end(JSON.stringify({ code: 40300, message: '文档演示仅提供只读数据，请连接真实后端执行操作' })); return }
  res.end(JSON.stringify(ok(data)))
}).listen(port, '127.0.0.1', () => console.log(`Read-only documentation demo: ${origin}`))
