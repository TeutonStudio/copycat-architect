import { inspectGraph, applyGraphDiagram, remapBuildingReferences, newNode, starterBuilding, previewGraph, normalizeDocument } from './generated/engine.mjs'

const copy = value => structuredClone(value)
export const hasReferences = document => document.graph.nodes.some(node => node.kind === 'REFERENCE')
const wires = entries => entries.flatMap(({ node, ports }) => ports.filter(p => p.source).map(p => ({ source: p.source, target: node.id, port: p.id })))
const token = () => `ref_${crypto.randomUUID().slice(0, 13)}`

function dependencies(entries, output) {
  const byId = new Map(entries.map(entry => [entry.node.id, entry]))
  const included = new Set(), active = new Set()
  function visit(id) {
    if (active.has(id)) throw new Error('Zyklische Referenzverbindung.')
    if (included.has(id)) return
    const entry = byId.get(id)
    if (!entry) throw new Error(`Referenziertes Ergebnis fehlt: ${id}`)
    active.add(id)
    entry.ports.filter(p => p.source && p.source !== 'empty_wall').forEach(p => visit(p.source))
    active.delete(id); included.add(id)
  }
  visit(output)
  return entries.filter(entry => included.has(entry.node.id))
}

// Reference documents keep exact, embedded versions. Expansion is only an
// evaluator adapter: editor nodes, saved drafts and exports retain the reference.
export function expandReferences(document, library = { forms: {}, modules: {} }, stack = []) {
  if (!hasReferences(document)) return { document, library, bindings: new Map(), owned: new Set() }
  if (stack.length >= 8) throw new Error('Referenzen sind auf acht Verschachtelungen begrenzt.')
  const expanded = [], bindings = new Map(), owned = new Set(), owners = new Map(), additions = []
  let merged = { ...library, forms: { ...library.forms }, modules: { ...library.modules } }
  const groups = copy(document.graph.nodeGroups || [])
  for (const node of document.graph.nodes) {
    if (node.kind !== 'REFERENCE') { expanded.push(copy(node)); continue }
    const definition = node.reference
    if (!definition?.document || definition.schema !== 1 || !Number.isInteger(definition.version) || definition.version < 1) throw new Error(`Referenz „${node.name || node.id}“ ist unvollständig.`)
    const key = `${definition.id}@${definition.version}`
    if (stack.includes(key)) throw new Error(`Rekursive Referenz: ${key}`)
    const source = expandReferences(definition.document, definition.library, [...stack, key])
    const sourceEntries = inspectGraph(true, source.document)
    const palette = normalizeDocument(true, source.document).palette
    const members = dependencies(sourceEntries, definition.output)
    const map = Object.fromEntries(members.map((entry, i) => [entry.node.id, entry.node.id === definition.output ? node.id : `${node.id.slice(0, 23)}_m${i}`]))
    const namespace = `${node.id.slice(0, 20)}_`
    const formKeys = {}, moduleKeys = {}
    for (const [key, form] of Object.entries(source.library.forms || {})) {
      const id = `${namespace}f${Object.keys(formKeys).length}`
      formKeys[key] = `${id}@${form.version || 1}`
      merged.forms[formKeys[key]] = { ...copy(form), id }
    }
    for (const [key, module] of Object.entries(source.library.modules || {})) {
      const id = `${namespace}u${Object.keys(moduleKeys).length}`
      moduleKeys[key] = `${id}@${module.version || 1}`
      merged.modules[moduleKeys[key]] = { ...copy(module), id, cells: module.cells.map(cell => ({ ...copy(cell), form: formKeys[cell.form] || cell.form,
        surfaces: cell.surfaces || Object.fromEntries((source.library.forms[cell.form]?.groups || [{ id: 'body', role: 'surface' }]).map(g => [g.id, palette?.[g.role === 'surface' ? cell.role || 'wall' : g.role] || 'minecraft:stone'])) })) }
    }
    for (const { node: original } of members) {
      const next = { ...copy(original), ...remapBuildingReferences(original, map), id: map[original.id] }
      if (original.id === definition.output) { next.x = node.x; next.y = node.y }
      if (next.form) next.form = formKeys[next.form] || next.form
      if (next.module) next.module = moduleKeys[next.module] || next.module
      if (next.kind === 'COPYCAT' && !next.block && !next.surfaces) {
        const form = merged.forms[next.form]
        next.surfaces = Object.fromEntries((form?.groups || [{ id: 'body', role: 'surface' }]).map(g => [g.id,
          palette?.[g.role === 'surface' ? next.role || 'wall' : g.role] || 'minecraft:stone']))
      }
      if (original.id === definition.output && node.referenceOverrides) {
        if (next.kind !== 'COPYCAT') throw new Error('Die Instanzeinstellungen benötigen einen Copycat-Ausgang.')
        const overrides=copy(node.referenceOverrides)
        if (overrides.surfaces) {
          const groups=new Set((merged.forms[next.form]?.groups || [{id:'body'}]).map(g=>g.id))
          if (Object.keys(overrides.surfaces).some(id=>!groups.has(id))) throw new Error('Die gespeicherten Instanzmaterialien passen nicht zu den Materialgruppen der neuen Quelle.')
          overrides.surfaces={...next.surfaces,...overrides.surfaces}
        }
        Object.assign(next,overrides)
      }
      expanded.push(next); owned.add(next.id);owners.set(next.id,node.id)
    }
    // A referenced complete building is an internal result, not a second root.
    if (members.some(entry => entry.node.id === definition.output && entry.node.kind === 'BUILDING')) groups.push({ id: `${node.id}_g`, name: node.name, nodes: [node.id] })
    for (const input of definition.bindings || []) {
      const binding = { target: map[input.target], port: input.port }
      if (!binding.target) throw new Error('Die gespeicherte Referenzschnittstelle ist ungültig.')
      bindings.set(`${node.id}/${input.id}`, binding)
      if (node.referenceInputs?.[input.id]) additions.push({ ...binding, source: node.referenceInputs[input.id] })
    }
  }
  if (expanded.length > 96) throw new Error('Die aufgelösten Referenzen überschreiten die Grenze von 96 Gebäudeknoten.')
  let result = { ...document, graph: { ...document.graph, nodes: expanded, nodeGroups: groups } }
  if (additions.length) {
    const existing = wires(inspectGraph(true, result)).filter(w => !additions.some(a => a.target === w.target && a.port === w.port))
    result = { ...result, graph: applyGraphDiagram(true, result, expanded, [...existing, ...additions]) }
  }
  return { document: result, library: merged, bindings, owned, owners, managedGroups:groups.filter(g => !(document.graph.nodeGroups || []).some(original => original.id === g.id)).map(g => g.id) }
}

export function referenceEntries(document) {
  const expanded = expandReferences(document)
  const all = inspectGraph(true, expanded.document)
  const byId = new Map(all.map(entry => [entry.node.id, entry]))
  return document.graph.nodes.map(node => {
    const entry = byId.get(node.id)
    if (node.kind !== 'REFERENCE') return entry
    return { ...entry, node, sourceKind: entry.node.kind,
      ports: (node.reference.bindings || []).map(input => ({ ...input, source: node.referenceInputs?.[input.id] || '' })) }
  })
}

export function referenceWires(document, connections, removed = []) {
  const nodes = document.graph.nodes.filter(n => !removed.includes(n.id))
  const candidate = { ...document, graph: { ...document.graph, nodes } }
  const expanded = expandReferences(document)
  const layout = expanded.document.graph.nodes.filter(n => !removed.includes(n.id) && !removed.includes(expanded.owners?.get(n.id)))
  const kept = new Set(layout.map(n => n.id))
  const bindings = new Set([...expanded.bindings.values()].map(b => `${b.target}/${b.port}`))
  const internal = wires(inspectGraph(true, expanded.document)).filter(w => kept.has(w.source) && kept.has(w.target) && expanded.owned.has(w.target) && !bindings.has(`${w.target}/${w.port}`))
  const outer = connections.filter(w => !removed.includes(w.source) && !removed.includes(w.target))
  const nativeWires = outer.map(w => ({ ...w, ...(expanded.bindings.get(`${w.target}/${w.port}`) || {}) }))
  const graph = applyGraphDiagram(true, expanded.document, layout, [...internal, ...nativeWires])
  const native = new Map(graph.nodes.map(n => [n.id, n]))
  return { ...candidate, graph: { ...candidate.graph, nodes: nodes.map(n => n.kind === 'REFERENCE' ? { ...n,
    referenceInputs: Object.fromEntries(outer.filter(w => w.target === n.id).map(w => [w.port, w.source])) } : native.get(n.id)) } }
}

export function createReferenceDefinition(document, library, output, identity, version = 1) {
  const resolved = expandReferences(document, library)
  const entries = inspectGraph(true, resolved.document)
  const selected = entries.find(entry => entry.node.id === output)
  if (!selected || !selected.outputType) throw new Error('Ein typisiertes Ergebnis des gespeicherten Graphen auswählen.')
  const members = dependencies(entries, output)
  const bindings = members.flatMap(entry => entry.ports.filter(p => !p.source || p.source === 'empty_wall').map(p => ({
    id: `input${entry.node.id.length}_${entry.node.id}_${p.id}`, target: entry.node.id, port: p.id,
    name: `${entry.node.name || entry.node.kind} · ${p.id}`, accepts: p.accepts,
  })))
  return { schema: 1, id: identity, version, output, document: copy(document), library: copy(library), bindings }
}

export function applyReference({ document, library, nodeId, option }) {
  const definition = createReferenceDefinition(option.document, option.library, option.output, option.identity, option.version || 1)
  const previous = document.graph.nodes.find(node => node.id === nodeId)
  if (previous && ['BUILDING', 'OUTPUT'].includes(previous.kind)) throw new Error('Der Ergebnisknoten kann keine Referenz werden.')
  const referenceInputs = {}
  if (previous) {
    const entries = hasReferences(document) ? referenceEntries(document) : inspectGraph(true,document)
    for (const port of entries.find(e => e.node.id === previous.id).ports.filter(p => p.source && p.source !== 'empty_wall')) {
      const binding = definition.bindings.find(b => b.id === port.id || b.target === definition.output && b.port === port.id)
      if (!binding) throw new Error(`Der belegte Eingang „${port.name || port.id}“ passt nicht zur neuen Quelle. Verbindung zuerst gezielt trennen.`)
      referenceInputs[binding.id] = port.source
    }
  }
  const overrides = copy(previous?.referenceOverrides || instanceOverrides(previous))
  const node = { id: previous?.id || token(), kind: 'REFERENCE', name: option.name,
    x: previous?.x ?? 40, y: previous?.y ?? 100, reference: definition, referenceInputs,
    ...(Object.keys(overrides).length ? {referenceOverrides:overrides} : {}) }
  const next = { ...document, referenceSchema: 1, graph: { ...document.graph,
    nodes: previous ? document.graph.nodes.map(n => n.id === nodeId ? node : n) : [...document.graph.nodes, node] } }
  // Native validation also rejects incompatible existing outgoing connections.
  referenceEntries(next)
  return { document: next, library, nodeId: node.id }
}

export function previewReference(option) {
  const expanded = expandReferences(option.document, option.library)
  return previewGraph(true, expanded.document, expanded.library, option.output)
}

export function moduleOption(key, module, library) {
  const node = { ...newNode(true, 'SEGMENT', 'template', 40, 100), module: key, assembly: false, inputs: [], length: module.width, height: module.height, depth: module.depth }
  const document = { schema: 2, name: module.name, palette: starterBuilding().palette,
    graph: { schema: 2, nodes: [{ id: 'empty_wall', kind: 'SEGMENT', block: 'minecraft:air', length: 1 }, node, newNode(true, 'BUILDING', 'result', 500, 100)] } }
  return { id: `module:${key}`, name: module.name, type: 'SEGMENT', identity: module.id || key, version: module.version || 1, output: 'template', document, library }
}

// Apply native document/group edits while retaining opaque reference snapshots.
export function foldReferenceEdit(original,resolved,edited) {
 const references=new Map(original.graph.nodes.filter(n=>n.kind==='REFERENCE').map(n=>[n.id,n]))
 return {...original,graph:{...edited.graph,
  nodes:edited.graph.nodes.filter(n=>references.has(n.id)||!resolved.owned.has(n.id)).map(n=>references.get(n.id)||n),
  nodeGroups:(edited.graph.nodeGroups||[]).filter(g=>!resolved.managedGroups.includes(g.id))
 }}
}

export function instanceOverrides(node) {
 if (node?.kind !== 'COPYCAT') return {}
 return {...(node.quarterTurns ? {quarterTurns:node.quarterTurns,rotationAxis:node.rotationAxis || 'Y'} : {}),
  ...(node.surfaces && Object.keys(node.surfaces).length ? {surfaces:copy(node.surfaces)} : {})}
}

export function copycatFromReference(node) {
 const result={...newNode(true,'COPYCAT',node.id,node.x,node.y),name:node.name,...copy(node.referenceOverrides || {})}
 for (const [input,source] of Object.entries(node.referenceInputs || {})) {
  const binding=node.reference.bindings.find(b=>b.id===input)
  if (!binding || binding.target!==node.reference.output || !['rotation','mirroring'].includes(binding.port))
   throw new Error('Ein belegter Eingang passt nicht zur Copycat-Quelle. Verbindung zuerst gezielt trennen.')
  result[binding.port]=source
 }
 return result
}
