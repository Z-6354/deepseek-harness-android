import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { createContext, runInContext } from 'node:vm'
import { getEventListeners, setMaxListeners } from 'node:events'

const script = readFileSync(new URL('../shell/assets/web-compat.js', import.meta.url), 'utf8')
function page({ native = false, missingWeakApi } = {}) {
  // A separate static namespace leaves Node's actual AbortSignal unmodified.
  function Signal() {}
  Signal.prototype = AbortSignal.prototype
  if (native) Signal.any = AbortSignal.any
  const warnings = []
  const context = createContext({ AbortSignal: Signal, AbortController, EventTarget, sources: [], console: { warn: value => warnings.push(value) } })
  if (missingWeakApi) runInContext(`globalThis.${missingWeakApi}=undefined`, context)
  if (native) runInContext('Promise.withResolvers = function nativeWithResolvers() {}', context)
  else runInContext('delete Promise.withResolvers', context)
  const originals = runInContext('[Promise.withResolvers, AbortSignal.any]', context)
  runInContext(script, context)
  return { context, originals, warnings, run: source => runInContext(source, context) }
}

test('missing Promise.withResolvers resolves, rejects and assimilates promises', async () => {
  const p = page()
  const deferred = p.run('Promise.withResolvers()')
  assert.deepEqual(Object.keys(deferred), ['promise', 'resolve', 'reject'])
  deferred.resolve(Promise.resolve('ready'))
  deferred.reject(new Error('ignored'))
  assert.equal(await deferred.promise, 'ready')
  const rejected = p.run('Promise.withResolvers()')
  const error = new Error('rejected')
  rejected.reject(error)
  await assert.rejects(rejected.promise, value => value === error)
})

test('Promise.withResolvers uses its constructor and rejects invalid capabilities', async () => {
  const p = page()
  assert.equal(p.run('class Sub extends Promise {}; Promise.withResolvers.call(Sub).promise instanceof Sub'), true)
  for (const receiver of ['null', '{}', '(() => {})', 'function NotExecutor() {}', 'function BadExecutor(exec) {exec(1, 2)}']) {
    assert.throws(() => p.run(`Promise.withResolvers.call(${receiver})`), { name: 'TypeError' })
  }
  assert.throws(() => p.run('Promise.withResolvers.call(function Double(exec) {exec(()=>{},()=>{});exec(()=>{},()=>{})})'), { name: 'TypeError' })
  assert.equal(p.run('typeof Promise.withResolvers.call(function InitiallyUndefined(exec) {exec(undefined,undefined);exec(()=>{},()=>{})}).resolve'), 'function')
  assert.equal(p.run('Object.getOwnPropertyDescriptor(Promise,"withResolvers").enumerable'), false)
  assert.equal(p.run('Promise.withResolvers.length'), 0)
})

test('native implementations are preserved and repeated installation is idempotent', () => {
  const p = page({ native: true })
  assert.equal(p.run('Promise.withResolvers'), p.originals[0])
  assert.equal(p.run('AbortSignal.any'), p.originals[1])
  const missing = page()
  const installed = missing.run('[Promise.withResolvers, AbortSignal.any]')
  runInContext(script, missing.context)
  assert.equal(missing.run('Promise.withResolvers'), installed[0])
  assert.equal(missing.run('AbortSignal.any'), installed[1])
})

test('AbortSignal.any creates an independent empty signal and preserves first pre-aborted reason', () => {
  const p = page()
  const empty = p.run('AbortSignal.any([])')
  assert.equal(empty.aborted, false)
  const one = new AbortController(), two = new AbortController()
  const reason = { reason: 'first' }
  one.abort(reason); two.abort('second')
  p.context.sources = [one.signal, two.signal]
  const result = p.run('AbortSignal.any(sources)')
  assert.equal(result.aborted, true)
  assert.equal(result.reason, reason)
  assert.equal(getEventListeners(one.signal, 'abort').length, 0)
  assert.equal(getEventListeners(two.signal, 'abort').length, 0)
  assert.equal(p.run('AbortSignal.any.length'), 1)
})

test('AbortSignal.any validates the entire iterable including entries after an aborted source', () => {
  const p = page()
  assert.equal(p.run('typeof AbortSignal.any'), 'function')
  for (const expression of ['undefined', 'null', '1', '""', '({length:0})', '[{}]', '[null]', '[{aborted:false,reason:undefined}]']) {
    assert.throws(() => p.run(`AbortSignal.any(${expression})`), { name: 'TypeError' })
  }
  const source = new AbortController(); source.abort('already')
  p.context.sources = [source.signal, {}]
  assert.throws(() => p.run('AbortSignal.any(sources)'), { name: 'TypeError' })
  assert.equal(getEventListeners(source.signal, 'abort').length, 0)
})

test('AbortSignal.any consumes a custom iterable once and closes it on invalid input', () => {
  const p = page()
  assert.equal(p.run(`let gets=0, closed=false;
    const input={get [Symbol.iterator](){ gets++;return function*(){try {yield {};yield null} finally {closed=true}}}};
    try {AbortSignal.any(input)} catch(error) {if(error.name!=='TypeError')throw error}
    gets===1 && closed`), true)
})

test('first subsequent abort preserves reason, fires once and removes every source listener', () => {
  const p = page()
  const one = new AbortController(), two = new AbortController()
  p.context.sources = [one.signal, one.signal, two.signal]
  const result = p.run('AbortSignal.any(sources)')
  assert.equal(getEventListeners(one.signal, 'abort').length, 1)
  assert.equal(getEventListeners(two.signal, 'abort').length, 1)
  let count = 0
  result.addEventListener('abort', () => count++)
  two.signal.dispatchEvent(new Event('abort'))
  assert.equal(result.aborted, false)
  assert.equal(getEventListeners(two.signal, 'abort').length, 1)
  const reason = new Error('cancelled')
  two.abort(reason); one.abort('too late')
  assert.equal(result.reason, reason)
  assert.equal(count, 1)
  assert.equal(getEventListeners(one.signal, 'abort').length, 0)
  assert.equal(getEventListeners(two.signal, 'abort').length, 0)
})

test('abort chains propagate synchronously with the default AbortError', () => {
  const p = page()
  const source = new AbortController()
  p.context.sources = [source.signal]
  const result = p.run('const combined=AbortSignal.any(sources);AbortSignal.any([combined])')
  source.abort()
  assert.equal(result.aborted, true)
  assert.equal(result.reason.name, 'AbortError')
  assert.equal(result.reason, source.signal.reason)
})

test('missing weak GC primitives leave AbortSignal.any unavailable with an explicit diagnostic', () => {
  for (const missingWeakApi of ['WeakRef', 'FinalizationRegistry']) {
    const p = page({ missingWeakApi })
    assert.equal(p.run('typeof AbortSignal.any'), 'undefined')
    assert.equal(p.run('typeof Promise.withResolvers'), 'function')
    assert.equal(p.warnings.length, 1)
    assert.match(p.warnings[0], /update Android System WebView/)
    const native = page({ native: true, missingWeakApi })
    assert.equal(native.run('AbortSignal.any'), native.originals[1])
    assert.equal(native.warnings.length, 0)
  }
})

const nextTurn = () => new Promise(resolve => setImmediate(resolve))
test('discarded combined signals become collectible and remove all 1000 source listeners', async () => {
  assert.equal(typeof globalThis.gc, 'function', 'Run this regression with node --expose-gc --test')
  const p = page()
  const source = new AbortController()
  setMaxListeners(0, source.signal)
  p.context.sources = [source.signal]
  p.run('for(let i=0;i<1000;i++)AbortSignal.any(sources);undefined')
  assert.equal(getEventListeners(source.signal, 'abort').length, 1000)
  const deadline = Date.now() + 5000
  // Finalization is scheduled nondeterministically: yield complete turns between forced GCs,
  // and observe the real listeners until the bounded deadline instead of sleeping once.
  while (getEventListeners(source.signal, 'abort').length && Date.now() < deadline) {
    await nextTurn()
    globalThis.gc()
    await nextTurn()
  }
  assert.equal(getEventListeners(source.signal, 'abort').length, 0)
  assert.equal(source.signal.aborted, false, 'GC cleanup must not abort the source')
})

test('a live combined signal keeps its controller across GC and still aborts with the first reason', async () => {
  assert.equal(typeof globalThis.gc, 'function', 'Run this regression with node --expose-gc --test')
  const p = page()
  const one = new AbortController(), two = new AbortController()
  p.context.sources = [one.signal, two.signal]
  const live = p.run('AbortSignal.any(sources)')
  for (let i=0;i<30;i++) { await nextTurn(); globalThis.gc(); await nextTurn() }
  assert.equal(live.aborted, false)
  assert.equal(getEventListeners(one.signal, 'abort').length, 1)
  assert.equal(getEventListeners(two.signal, 'abort').length, 1)
  const first = { first: 'reason' }
  one.abort(first); two.abort('later')
  assert.equal(live.aborted, true)
  assert.equal(live.reason, first)
  assert.equal(getEventListeners(one.signal, 'abort').length, 0)
  assert.equal(getEventListeners(two.signal, 'abort').length, 0)
})
