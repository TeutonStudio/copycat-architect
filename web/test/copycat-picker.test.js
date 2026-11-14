import assert from 'node:assert/strict'
import test from 'node:test'
import { listCopycatOptions, previewCopycatOption } from '../src/copycat-options.js'
import { createCopycatPreviewClient } from '../src/copycat-preview-client.js'
import { model, readGraph } from '../src/model.js'

const form = (source, name = 'Testform') => ({ schema: 3, name, parts: [{ source }] })
const option = (source, materials = {}) => ({ id: 'same-entry', document: form(source), materials })
const slab = () => option('copycats:copycat_slab')
const full = () => option('copycats:copycat_block')
const stairs = () => option('copycats:copycat_stairs')

class FakeWorker {
  messages = []
  terminated = false
  postMessage(message) { this.messages.push(structuredClone(message)) }
  complete() {
    const { id, option } = this.messages.at(-1)
    try { this.onmessage({ data: { id, plan: previewCopycatOption(option) } }) }
    catch (error) { this.onmessage({ data: { id, error: error.message } }) }
  }
  terminate() { this.terminated = true }
}
function setup(options) {
  const worker = new FakeWorker()
  let creations = 0
  const client = createCopycatPreviewClient({ workerFactory: () => { creations++; return worker }, ...options })
  return { worker, client, creations: () => creations }
}
async function compile(client, worker, source) {
  const promise = client.request(source)
  worker.complete()
  return await promise
}

test('the catalogue contains every adapter, personal form and exact library reference without compiling saved documents', () => {
  const saved = { id: 'personal', name: 'Persönliche Treppe', mode: 'form', document: form('copycats:copycat_stairs'), materials: { body: 'minecraft:stone' } }
  const invalid = { id: 'broken', name: 'Fehlerhafte Form', mode: 'form', document: form('missing:shape') }
  const reference = form('copycats:copycat_slab', 'Bibliotheksstufe')
  const options = listCopycatOptions({ savedForms: [saved, invalid, { id: 'building', mode: 'building' }], library: { forms: { 'slab@3': reference } } })
  const basic = options.filter(item => item.kind === 'base')
  assert.deepEqual(basic.map(item => item.source), model.shapes.map(shape => shape.id))
  assert.equal(options.length, model.shapes.length + 4)
  assert.equal(new Set(options.map(item => item.id)).size, options.length)
  assert.ok(basic.every(item => item.category === 'basic' && item.entry.document === item.document))
  const personal = options.find(item => item.id === 'saved:personal')
  assert.equal(personal.entry, saved)
  assert.equal(personal.document, saved.document)
  assert.equal(personal.materials, saved.materials)
  const library = options.find(item => item.id === 'library:slab@3')
  assert.equal(library.reference, 'slab@3')
  assert.equal(library.document, reference)
  assert.equal(library.category, 'saved')
  assert.throws(() => previewCopycatOption(options.find(item => item.id === 'saved:broken')), /Unsupported base shape/)
})

test('all basic thumbnails compile real adapter geometry, including the stepped roof', () => {
  const counts = new Map(listCopycatOptions().filter(item => item.kind !== 'empty').map(item => [item.source, previewCopycatOption(item).count]))
  assert.equal(counts.get('copycats:copycat_block'), 4096)
  assert.equal(counts.get('copycats:copycat_slab'), 2048)
  assert.equal(counts.get('copycats:copycat_stairs'), 3072)
  assert.equal(counts.get('copycats:copycat_byte'), 512)
  assert.equal(counts.get('copycat_architect:copycat_roof'), 720)
  assert.ok([...counts.values()].every(count => count > 0 && count <= 4096))
})

test('saved previews compile the graph rather than stale parts and preserve material states', () => {
  const document = readGraph(form('copycats:copycat_slab'), 'form')
  document.parts = [{ source: 'copycats:copycat_block' }]
  const before = JSON.stringify(document)
  const plan = previewCopycatOption({ document, materials: { body: ' minecraft:oak_log[axis=x] ', unused: 'bad material' } })
  assert.equal(plan.count, 2048)
  assert.deepEqual(plan.meshes.map(mesh => mesh.material), ['minecraft:oak_log[axis=x]'])
  assert.deepEqual(Array.from(plan.meshes[0].boxes[0]), [0, 0, 0, 16, 8, 16])
  assert.equal(JSON.stringify(document), before)
  assert.equal(previewCopycatOption({ document, materials: { body: '' } }).meshes[0].material, 'body')
  assert.throws(() => previewCopycatOption({ document, materials: { body: 'bad material' } }), /Ungültige Ressourcen-ID/)
})

test('saved output display filters never hide geometry that the selected Copycat will import', () => {
  const document = readGraph({
    schema: 3, name: 'Stufe mit oberer Schicht',
    groups: [
      { id: 'body', name: 'Body', role: 'surface', priority: 0 },
      { id: 'cap', name: 'Cap', role: 'surface', priority: 1 },
    ],
    parts: [
      { source: 'copycats:copycat_slab', group: 'body' },
      { source: 'copycats:copycat_layer', group: 'cap', top: true },
    ],
  }, 'form')
  const expected = previewCopycatOption({ document })
  document.graph.nodes.find(node => node.kind === 'OUTPUT').previewGroup = 'cap'
  const before = JSON.stringify(document)
  const actual = previewCopycatOption({ document })
  assert.deepEqual(actual, expected)
  assert.equal(actual.count, 2560)
  assert.deepEqual(actual.meshes.map(mesh => mesh.material).sort(), ['body', 'cap'])
  const visibleVolume = actual.meshes.reduce((sum, mesh) => sum + mesh.boxes.reduce(
    (sum, [x, y, z, maxX, maxY, maxZ]) => sum + (maxX - x) * (maxY - y) * (maxZ - z), 0), 0)
  assert.equal(visibleVolume, actual.count)
  assert.equal(JSON.stringify(document), before)
})

test('invalid and empty saved forms produce explicit compiler failures', () => {
  assert.throws(() => previewCopycatOption({ document: null }), /JSON-Objekt/)
  assert.throws(() => previewCopycatOption(option('unknown:shape')), /Unsupported base shape/)
  assert.throws(() => previewCopycatOption({ document: { schema: 3, name: 'Empty', parts: [] } }), /A form needs 1–32 parts/)
})

test('the preview client is lazy, deduplicates requests and serializes compilations', async () => {
  const { client, worker, creations } = setup()
  assert.equal(creations(), 0)
  const first = client.request(slab())
  const duplicate = client.request(slab())
  const second = client.request(full())
  assert.equal(first, duplicate)
  assert.equal(creations(), 1)
  assert.equal(worker.messages.length, 1)
  worker.complete()
  assert.equal((await first).count, 2048)
  assert.equal(worker.messages.length, 2)
  worker.complete()
  assert.equal((await second).count, 4096)
  assert.equal((await client.request(slab())).count, 2048)
  assert.equal(worker.messages.length, 2)
  client.dispose()
  assert.equal(worker.terminated, true)
})

test('selecting an already queued card promotes it ahead of other thumbnails', async () => {
  const { client, worker } = setup()
  const first = client.request(slab())
  const second = client.request(full())
  const third = client.request(stairs())
  assert.equal(client.request(stairs(), { priority: true }), third)
  worker.complete()
  await first
  assert.equal(worker.messages[1].option.document.parts[0].source, 'copycats:copycat_stairs')
  worker.complete()
  assert.equal((await third).count, 3072)
  assert.equal(worker.messages[2].option.document.parts[0].source, 'copycats:copycat_block')
  worker.complete()
  await second
  client.dispose()
})

test('cache revisions include geometry and materials and queued requests hold a snapshot', async () => {
  const { client, worker } = setup()
  const first = client.request(full())
  const saved = slab()
  const queued = client.request(saved)
  saved.document.parts[0].source = 'copycats:copycat_stairs'
  worker.complete()
  await first
  worker.complete()
  assert.equal((await queued).count, 2048)
  const revised = await compile(client, worker, saved)
  assert.equal(revised.count, 3072)
  saved.materials.body = 'minecraft:bricks'
  assert.equal((await compile(client, worker, saved)).meshes[0].material, 'minecraft:bricks')
  assert.equal(worker.messages.length, 4)
  client.dispose()
})

test('the preview cache evicts least recently used results at its configured bound', async () => {
  const { client, worker } = setup({ cacheSize: 2 })
  await compile(client, worker, slab())
  await compile(client, worker, full())
  await client.request(slab())
  await compile(client, worker, stairs())
  assert.equal(worker.messages.length, 3)
  await client.request(slab())
  await compile(client, worker, full())
  assert.equal(worker.messages.length, 4)
  client.dispose()
})

test('an invalid card rejects only its own preview and the queue continues', async () => {
  const { client, worker } = setup()
  const invalid = client.request(option('missing:shape'))
  const rejected = assert.rejects(invalid, /Unsupported base shape/)
  const valid = client.request(slab())
  worker.complete()
  await rejected
  worker.complete()
  assert.equal((await valid).count, 2048)
  client.dispose()
})

test('closing the dialog terminates the worker and rejects active, queued and future requests', async () => {
  const { client, worker } = setup()
  const promises = [client.request(slab()), client.request(full())].map(promise => assert.rejects(promise, { name: 'AbortError' }))
  client.dispose()
  client.dispose()
  await Promise.all(promises)
  await assert.rejects(client.request(stairs()), { name: 'AbortError' })
  assert.equal(worker.terminated, true)
  assert.equal(worker.onmessage, null)
  assert.equal(worker.messages.length, 1)
})

test('worker startup and runtime failures reject every affected request without hanging', async () => {
  const unavailable = createCopycatPreviewClient({ workerFactory: () => { throw new Error('Worker unavailable') } })
  await assert.rejects(unavailable.request(slab()), /Worker unavailable/)
  unavailable.dispose()
  const { client, worker } = setup()
  const promises = [client.request(slab()), client.request(full())].map(promise => assert.rejects(promise, /Worker crashed/))
  worker.onerror({ message: 'Worker crashed', preventDefault() {} })
  await Promise.all(promises)
  await assert.rejects(client.request(stairs()), /Worker crashed/)
  assert.equal(worker.terminated, true)
  client.dispose()
})


test('reference preview jobs include the selected output and frozen library in their cache key', async () => {
  const {client,worker}=setup()
  const {starterBuilding}=await import('../src/generated/engine.mjs')
  const {readFileSync}=await import('node:fs')
  const library=JSON.parse(readFileSync(new URL('../../examples/library.json',import.meta.url)))
  const option={referenceOption:true,document:starterBuilding(),library,output:'wall_copycat'}
  assert.equal((await compile(client,worker,option)).count,1)
  assert.equal(worker.messages[0].option.referenceOption,true)
  assert.equal(worker.messages[0].option.output,'wall_copycat')
  const wall=await compile(client,worker,{...option,output:'wall'})
  assert.ok(wall.count>1)
  assert.equal(worker.messages.length,2)
  client.dispose()
})
