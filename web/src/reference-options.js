import { newNode, starterBuilding } from './generated/engine.mjs'
import { listCopycatOptions } from './copycat-options.js'
import { inspect, readGraph } from './model.js'
import { insertNodeGroup } from './node-groups.js'
import { moduleOption } from './references.js'
import renaissance from './data/german-renaissance.json' with { type: 'json' }

export const styleLibrary = renaissance
const cache = new WeakMap()
export function listReferenceOptions({ mode = 'building', savedForms = [], savedGraphs = [], savedGroups = [], library = {} } = {}) {
  const forms = listCopycatOptions({ savedForms, library }).map(option => ({ ...option, path: [option.category === 'basic' ? 'Grundformen' : 'Gespeicherte Formen', ...option.name.split('/').map(s => s.trim()).filter(Boolean)] }))
  const graphs = savedGraphs.filter(entry => entry.mode === 'building' && mode === 'building').map(entry => ({
    id: `graph:${entry.id}`, kind: 'graph', category: 'graphs', name: entry.name, identity: entry.graphId || entry.id,
    version: entry.version || 1, entry, path: ['Gespeicherte Graphen', ...entry.name.split('/').map(s => s.trim()).filter(Boolean)],
  }))
  const groups = savedGroups.filter(entry => mode === 'building' || entry.mode === 'form').map(entry => ({
    id: `group:${entry.id}`, kind: 'group', category: 'groups', name: entry.name, identity: entry.graphId || entry.id,
    version: entry.version || 1, entry, path: ['Gruppen und Karten', ...entry.name.split('/').map(s => s.trim()).filter(Boolean)],
  }))
  const templates = mode === 'building' ? Object.entries(renaissance.modules).map(([key, module]) => ({
    ...moduleOption(key, module, renaissance), kind: 'template', category: 'templates', path: module.name.split('/').map(s => s.trim()).filter(Boolean),
  })) : []
  const parts = Object.entries(renaissance.forms).map(([reference, document]) => ({
    id: `style-form:${reference}`, kind: 'saved', category: 'templates', name: document.name,
    path: document.name.split('/').map(s => s.trim()).filter(Boolean), document,
    materials: Object.fromEntries(document.groups.map(g => [g.id, {body:'minecraft:bricks',stone:'minecraft:smooth_sandstone',frame:'minecraft:dark_oak_planks',glass:'minecraft:glass'}[g.id]])),
    entry: { id: document.id, version: document.version, mode:'form', name:document.name, document },
  }))
  return [...forms, ...graphs, ...groups, ...templates, ...parts]
}

export function prepareReferenceOption(option, output) {
  if (!['graph', 'group', 'template'].includes(option.kind)) return option
  let prepared = cache.get(option)
  if (!prepared) {
    let document, library = option.library || option.entry?.library || { forms: {}, modules: {} }
    if (option.kind === 'template') document = option.document
    else if (option.kind === 'graph') document = readGraph(option.entry.document, 'building')
    else {
      const template = option.entry
      const mode = template.mode
      const base = mode === 'building' ? starterBuilding() : { schema:3,name:template.name,groups:template.groups,graph:{schema:2,nodes:[newNode(false,'OUTPUT','result',500,100)]} }
      let member = 0
      const inserted = insertNodeGroup(base, template, () => `member_${member++}`)
      document = inserted.document
      const originals = new Set(base.graph.nodes.map(n => n.id))
      const insertedEntries = inspect(mode, document).filter(e => !originals.has(e.node.id))
      const used = new Set(insertedEntries.flatMap(e => e.ports.map(p => p.source)))
      const terminal = insertedEntries.filter(e => !used.has(e.node.id))
      if (mode === 'form') {
        if (terminal.length !== 1) throw new Error('Diese Formgruppe benötigt genau einen Ergebnisausgang.')
        const root = document.graph.nodes.find(n => n.kind === 'OUTPUT')
        document = { ...document, graph: { ...document.graph, nodes: document.graph.nodes.map(n => n.id === root.id ? {...n,inputs:[terminal[0].node.id]} : n) } }
        return { ...option, kind: 'saved', document, materials: template.materials, entry: { id: template.id, name:template.name, mode:'form', document, materials:template.materials } }
      }
      prepared = { document, library, outputs: terminal }
    }
    if (!prepared) {
      const entries = inspect('building', document)
      prepared = { document, library, outputs: entries.filter(e => e.node.id !== 'empty_wall' && e.outputType) }
    }
    cache.set(option, prepared)
  }
  const initial = option.output || prepared.outputs.find(e => e.node.kind === 'BUILDING')?.node.id || prepared.outputs[0]?.node.id
  const selected = prepared.outputs.find(e => e.node.id === (output || initial))
  if (!selected) throw new Error('Die gespeicherte Gruppe hat keinen verwendbaren Ausgang.')
  return { ...option, ...prepared, output: selected.node.id, type: selected.outputType, referenceOption: true }
}
