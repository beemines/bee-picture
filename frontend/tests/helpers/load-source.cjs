/* eslint-disable @typescript-eslint/no-require-imports -- Node harness for compiled SFC setup code. */
const { readFileSync } = require('node:fs')
const path = require('node:path')
const { createRequire } = require('node:module')
const ts = require('typescript')
const { parse, compileScript } = require('@vue/compiler-sfc')

// Same source-loading approach as the auth regressions, with injectable timer boundaries.
function loadSource(relativePath, replacements = {}, globals = {}) {
  const filename = path.resolve(__dirname, '../..', path.extname(relativePath) ? relativePath : relativePath + '.ts')
  let source = readFileSync(filename, 'utf8')
  if (filename.endsWith('.vue')) {
    source = compileScript(parse(source, { filename }).descriptor, { id: 'picture-flow-regression' }).content
  }
  const compiled = ts.transpileModule(source, {
    fileName: filename,
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022, esModuleInterop: true },
  }).outputText
  const nativeRequire = createRequire(filename)
  const module = { exports: {} }
  const requireSource = (name) => {
    if (Object.hasOwn(replacements, name)) return replacements[name]
    if (name.startsWith('@/')) return loadSource('src/' + name.slice(2), replacements, globals)
    return nativeRequire(name)
  }
  new Function('require', 'module', 'exports', ...Object.keys(globals), compiled)(
    requireSource, module, module.exports, ...Object.values(globals),
  )
  return module.exports
}

module.exports = { loadSource }
