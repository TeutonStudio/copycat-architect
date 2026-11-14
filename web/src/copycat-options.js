import { previewReference } from './references.js'
import { previewGraph } from './generated/engine.mjs'
import { model, readGraph } from './model.js'
import { parseMaterial } from './materials.js'

export const copycatShapeNames = {
  full: 'Vollblock', slab: 'Stufe', stairs: 'Treppe', panel: 'Panel',
  slice: 'Gerade Leiste', vertical_slice: 'Vertikale Leiste', corner_slice: 'Eckscheibe',
  layer: 'Schicht', beam: 'Balken', byte: 'Byte', step: 'Feste Leiste',
  vertical_step: 'Feste vertikale Leiste', half_panel: 'Halbes Panel', flat_pane: 'Mittige Scheibe',
  byte_panel: 'Byte-Panel', half_layer: 'Halbe Schicht', vertical_half_layer: 'Vertikale halbe Schicht',
  stacked_half_layer: 'Gestapelte halbe Schicht', roof_template: 'Dachvorlage',
}

const nameOf = (name, fallback) => typeof name === 'string' && name.trim() ? name.trim() : fallback

// Build labels and references only. Saved documents are parsed when their card
// becomes visible or the user selects them, not while opening the catalogue.
export function listCopycatOptions({ savedForms = [], library = {} } = {}) {
  const empty = { schema:3,name:'Leere Copycat',graph:{schema:2,nodes:[
    {id:'empty',kind:'SAVED',savedCopycat:{name:'Leere Copycat',version:1,parts:[]}},
    {id:'result',kind:'OUTPUT',inputs:['empty']},
  ]} }
  const emptyOption = {id:'empty',kind:'empty',category:'basic',name:'Leere Copycat',document:empty,materials:{},entry:{id:'empty',name:'Leere Copycat',mode:'form',document:empty,materials:{}}}
  const basic = model.shapes.map(shape => {
    const name = copycatShapeNames[shape.name] || shape.name
    const document = { schema: 3, name, parts: [{ source: shape.id }] }
    const materials = {}
    return {
      id: `base:${shape.id}`, kind: 'base', category: 'basic', name, source: shape.id,
      document, materials, entry: { id: `base_${shape.id}`, name, mode: 'form', document, materials },
    }
  })
  const saved = savedForms.filter(entry => entry && (!entry.mode || entry.mode === 'form')).map((entry, index) => ({
    id: `saved:${entry.id ?? index}`, kind: 'saved', category: 'saved',
    name: nameOf(entry.name, nameOf(entry.document?.name, 'Unbenannte Form')),
    document: entry.document, materials: entry.materials || {}, entry,
  }))
  const referenced = Object.entries(library.forms || {}).map(([reference, document]) => {
    const name = nameOf(document?.name, reference)
    const materials = {}
    return {
      id: `library:${reference}`, kind: 'library', category: 'saved', name, reference, document, materials,
      entry: { id: `library_${reference}`, name, mode: 'form', document, materials },
    }
  })
  return [emptyOption, ...basic, ...saved, ...referenced]
}

export function previewCopycatOption(option) {
  if (option.referenceOption) return previewReference(option)
  const source = readGraph(option.document, 'form')
  // An editor's output filter is only a viewing preference. Loading a saved
  // Copycat imports every material group, so its selection preview must too.
  const document = { ...source, graph: { ...source.graph, nodes: source.graph.nodes.map(node =>
    node.kind === 'OUTPUT' ? { ...node, previewGroup: '' } : node) } }
  const plan = previewGraph(false, document, {}, '')
  const materials = option.materials || {}
  const mapped = new Map()
  return {
    ...plan,
    meshes: plan.meshes.map(mesh => {
      if (!mapped.has(mesh.material)) {
        const value = materials[mesh.material]
        if (value != null && typeof value !== 'string') throw new Error(`Ungültiges Material für ${mesh.material}.`)
        const material = value?.trim()
        if (material) parseMaterial(material)
        mapped.set(mesh.material, material || mesh.material)
      }
      return { ...mesh, material: mapped.get(mesh.material) }
    }),
  }
}
