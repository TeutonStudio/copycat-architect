import { hasReferences, expandReferences, referenceEntries, referenceWires } from './references.js'
import { applyGraphDiagram, replaceGraphNodes, exportDocument, editInspectorNode, normalizeDocument, wireModels, compileForm, graphModels, inspectGraph, prepareForm } from './generated/engine.mjs'

export const model = { ...graphModels(), mirrorPlanes: wireModels().enums.CopycatMirrorPlane }
export const defaults = [{ id: 'body', name: 'Body', role: 'surface', priority: 0 }]
export const formGroups = document => document.groups ?? defaults
export const position = value => Math.max(model.positionMin, Math.min(model.positionMax, Math.round(value)))
export const inspect = (mode, document) => mode === 'building' && hasReferences(document) ? referenceEntries(document) : inspectGraph(mode === 'building', document)

// Preserve absent defaults and extension metadata. Native operations still own every
// recognized field, group reference and schema change. Arrays of nodes follow IDs.
function retainShape(raw, before, after) {
  if (JSON.stringify(before) === JSON.stringify(after)) return raw
  if (after == null || typeof after !== 'object') return after
  if (Array.isArray(after)) {
    const byId = after.length && after.every(item => item?.id) && Array.isArray(before)
    return after.map((item, i) => {
      const old = byId ? before.findIndex(old => old.id === item.id) : i
      return retainShape(raw?.[old], before?.[old], item)
    })
  }
  const result = { ...raw }
  for (const key of new Set([...Object.keys(before || {}), ...Object.keys(after)])) {
    const value = retainShape(raw?.[key], before?.[key], after[key])
    if (value === undefined) delete result[key]
    else result[key] = value
  }
  return result
}
function retainedGraph(document, graph) {
  const building = document.graph.nodes.some(node => node.kind === 'BUILDING')
  return retainShape(document.graph, normalizeDocument(building, document).graph, graph)
}
export function replaceNodes(document, nodes) {
  const candidate = { ...document, graph: { ...document.graph, nodes } }
  if (hasReferences(candidate)) { inspect('building', candidate); return {...candidate,referenceSchema:1} }
  return { ...candidate, graph: retainedGraph(candidate, replaceGraphNodes(nodes.some(node => node.kind === 'BUILDING'), hasReferences(document) ? candidate : document, nodes)) }
}

export function readGraph(raw, mode) {
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) throw new Error('Eine Graphdatei muss ein JSON-Objekt sein.')
  const bare = Array.isArray(raw.nodes)
  if (!bare && !raw.graph && !Array.isArray(raw.parts) && !raw.wings && !raw.patterns && !raw.palette) {
    throw new Error('Die Datei enthält weder einen Copycat-Graphen noch eine Form.')
  }
  const isBuilding = bare ? raw.nodes.some(node => node.kind === 'BUILDING')
    : raw.graph ? raw.graph.nodes?.some(node => node.kind === 'BUILDING')
      : !raw.parts && (raw.wings || raw.patterns || raw.palette)
  if (Boolean(isBuilding) !== (mode === 'building')) throw new Error('Die Datei gehört zum anderen Editor.')
  let document = bare ? { name: 'Importierter Graph', schema: isBuilding ? 2 : 3, graph: raw } : raw
  if (!document.graph) {
    if (isBuilding) throw new Error('Diese Datei enthält einen älteren Gebäudeplan ohne Gebäudegraph.')
    document = { ...document, graph: { schema: 2, nodes: prepareForm(document) } }
  }
  inspect(mode, document)
  return document
}

export function writeGraph(mode, document) {
  inspect(mode, document)
  if (hasReferences(document)) return structuredClone(document)
  return retainShape(document, normalizeDocument(mode === 'building', document), exportDocument(mode === 'building', document))
}

export function connections(entries) {
  const known = new Set(entries.map(({ node }) => node.id))
  return entries.flatMap(({ node, ports }) => ports
    .filter(port => known.has(port.source))
    .map(port => ({ source: port.source, target: node.id, port: port.id })))
}

export function protectedNode(node) {
  return node.id === 'empty_wall' || ['OUTPUT', 'BUILDING'].includes(node.kind)
}

export function applyWires(mode, document, entries, wires, removed = []) {
  if (hasReferences(document)) return referenceWires(document, wires, removed)
  const layout = entries.map(({ node }) => node).filter(node => !removed.includes(node.id))
  const graph = applyGraphDiagram(mode === 'building', document, layout, wires)
  return { ...document, graph: retainedGraph(document, graph) }

}

export function validateLibrary(data) {
  if (!data || typeof data !== 'object' || Array.isArray(data) || !('forms' in data || 'modules' in data)) {
    throw new Error('Erwartet wird eine Copycat-Bibliothek mit forms und/oder modules.')
  }
  for (const key of ['forms', 'modules']) {
    if (key in data && (!data[key] || typeof data[key] !== 'object' || Array.isArray(data[key]))) {
      throw new Error(`Der Bibliothekseintrag ${key} muss ein Objekt sein.`)
    }
  }
  return data
}

export function editNode(mode, document, node) {
  if (hasReferences(document)) {
    if (node.kind === 'REFERENCE') return replaceNodes(document, document.graph.nodes.map(n => n.id === node.id ? node : n))
    const expanded = expandReferences(document)
    const edited = editInspectorNode(true, expanded.document, node)
    return replaceNodes(document, document.graph.nodes.map(n => n.kind === 'REFERENCE' ? n : edited.nodes.find(v => v.id === n.id)))
  }
  return { ...document, graph: retainedGraph(document, editInspectorNode(mode === 'building', document, node)) }
}
