import { remapBuildingReferences } from './generated/engine.mjs'
import { applyWires, connections, formGroups, inspect, protectedNode, replaceNodes } from './model.js'

export function saveNodeGroup(mode, document, ids, name, materials = {}, library = null) {
  const entries = inspect(mode, document)
  const selected = new Set(entries.filter(({ node }) => ids.includes(node.id) && !protectedNode(node)).map(({ node }) => node.id))
  if (!selected.size) throw new Error('Mindestens einen bearbeitbaren Knoten auswählen. Ergebnis und Standard-Leerwand gehören nicht zur Knotengruppe.')
  const used = new Set([formGroups(document)[0].id, ...entries.filter(({ node }) => selected.has(node.id)).flatMap(({ node }) => [
    node.part?.group, ...(node.savedCopycat?.parts || []).map(part => part.group),
    node.materialGroup, node.previewGroup, ...(node.inputGroups || []),
  ])])
  return { id: crypto.randomUUID(), name: name.trim() || 'Knotengruppe', mode, materials: structuredClone(materials), library: mode === 'building' ? structuredClone(library) : null,
    nodes: structuredClone(document.graph.nodes.filter(node => selected.has(node.id))),
    wires: connections(entries).filter(wire => selected.has(wire.source) && selected.has(wire.target)),
    groups: mode === 'form' ? structuredClone(formGroups(document).filter(group => used.has(group.id))) : [] }
}
export function insertNodeGroup(document, template, idFactory = () => `node_${crypto.randomUUID().slice(0, 13)}`) {
  const mode = template.mode
  const ids = new Map(template.nodes.map(node => [node.id, idFactory()]))
  const groups = structuredClone(formGroups(document))
  const groupIds = new Map()
  if (mode === 'form') for (const source of template.groups) {
    const existing = groups.find(group => group.id === source.id && group.name === source.name && group.role === source.role && group.priority === source.priority)
    if (existing) { groupIds.set(source.id, existing.id); continue }
    if (groups.length >= 8) throw new Error('Die Knotengruppe benötigt mehr als acht Materialgruppen. Zuerst Materialgruppen im Zielgraphen reduzieren.')
    const id = !groups.some(group => group.id === source.id) ? source.id : Array.from({ length: 100 }, (_, i) => `group${i + 1}`).find(id => groups.every(group => group.id !== id))
    const priority = groups.every(group => group.priority !== source.priority) ? source.priority : Array.from({ length: 65 }, (_, i) => i - 32).find(priority => groups.every(group => group.priority !== priority))
    groups.push({ ...source, id, priority }); groupIds.set(source.id, id)
  }
  if (mode === 'form') {
    const templateOrder = [...template.groups].sort((a, b) => a.priority - b.priority).map(group => groupIds.get(group.id))
    const byId = new Map(groups.map(group => [group.id, group]))
    if (templateOrder.some((id, index) => index && byId.get(id).priority <= byId.get(templateOrder[index - 1]).priority)) {
      // Preserve both the existing graph's foreground order and the template's
      // foreground order, even when their absolute priority values collide.
      const edges = new Map(groups.map(group => [group.id, new Set()]))
      for (const sequence of [[...formGroups(document)].sort((a, b) => a.priority - b.priority).map(group => group.id), templateOrder]) {
        sequence.slice(1).forEach((id, index) => edges.get(sequence[index]).add(id))
      }
      const pending = new Set(groups.map(group => group.id)), order = []
      while (pending.size) {
        const id = [...pending].find(id => ![...pending].some(other => edges.get(other).has(id)))
        if (!id) throw new Error('Die Materialprioritäten der Knotengruppe widersprechen sich.')
        order.push(id); pending.delete(id)
      }
      order.forEach((id, index) => { byId.get(id).priority = index - 32 })
    }
  }
  const left = Math.min(...template.nodes.map(node => node.x ?? 0)), top = Math.min(...template.nodes.map(node => node.y ?? 0))
  const right = Math.max(...document.graph.nodes.map(node => node.x ?? 0))
  const nodes = template.nodes.map(original => {
    const node = mode === 'building' && original.kind !== 'REFERENCE' ? { ...structuredClone(original), ...remapBuildingReferences(original, Object.fromEntries(ids)) } : structuredClone(original)
    if (node.kind === 'REFERENCE') node.referenceInputs = {}
    node.id = ids.get(original.id); node.x = Math.min(4096, right + 200 + (node.x ?? 0) - left); node.y = Math.min(4096, 80 + (node.y ?? 0) - top)
    if (mode === 'form') {
      node.inputs = (node.inputs || []).map(() => '')
      if (node.part) node.part.group = groupIds.get(node.part.group || template.groups[0]?.id) || groups[0].id
      if (node.savedCopycat) node.savedCopycat.parts = node.savedCopycat.parts.map(part => {
        const group = groupIds.get(part.group || 'body')
        if (!group) throw new Error('Der gespeicherten Copycat-Quelle fehlt eine Materialgruppe in der Knotengruppe.')
        return { ...part, group }
      })
      for (const key of ['materialGroup', 'previewGroup']) if (node[key]) node[key] = groupIds.get(node[key]) || ''
      if (node.inputGroups) node.inputGroups = node.inputGroups.map(id => groupIds.get(id) || '')
      if (node.materialMode === 'GROUPED' && !node.materialGroup) node.materialGroup = groupIds.get(template.groups[0]?.id) || groups[0].id
    } else {
      node.inputs = (node.inputs || []).map(() => '')
      for (const key of ['floor', 'ceiling', 'corner', 'roof', 'rotation', 'mirroring']) if (key in node) node[key] = ''
      node.walls = Object.fromEntries(Object.keys(node.walls || {}).map(key => [key, 'empty_wall']))
      node.levels = {}
    }
    return node
  })
  const result = replaceNodes(mode === 'form' ? { ...document, groups } : document, [...document.graph.nodes, ...nodes])
  const entries = inspect(mode, result)
  const wires = [...connections(inspect(mode, document)), ...template.wires.map(wire => ({ ...wire, source: ids.get(wire.source), target: ids.get(wire.target) }))]
  return { document: applyWires(mode, result, entries, wires), materials: Object.fromEntries(Object.entries(template.materials || {}).filter(([id]) => groupIds.has(id)).map(([id, material]) => [groupIds.get(id), material])) }
}

export function mergeLibraries(current, incoming) {
  if (!incoming) return current
  const result = { ...incoming, ...current }
  for (const type of ['forms', 'modules']) {
    for (const [id, entry] of Object.entries(incoming[type] || {})) if (current[type]?.[id] && JSON.stringify(current[type][id]) !== JSON.stringify(entry)) {
      throw new Error(`Bibliothekskonflikt: ${id} ist im Ziel mit anderem Inhalt vorhanden. Eine andere Version verwenden.`)
    }
    result[type] = { ...current[type], ...incoming[type] }
  }
  return result
}
