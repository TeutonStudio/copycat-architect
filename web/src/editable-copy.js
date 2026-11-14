import { newNode, normalizeDocument } from './generated/engine.mjs'
import { formGroups, readGraph } from './model.js'
import { expandReferences } from './references.js'
import { editableForm } from './form-recipes.js'

const clone=value=>structuredClone(value)
const copyName=name=>`${(name || 'Entwurf').split('/').at(-1).trim().slice(0,55)} · Kopie`

// Replace fixed one-layer modules with their real Copycat sources and arrangement.
// The private library remains a snapshot; editing one part creates a new form version.
function unpackModules(document,library) {
  const ids=new Set(document.graph.nodes.map(n=>n.id))
  let sequence=0
  const id=()=>{let value;do value=`copy_member_${sequence++}`;while(ids.has(value));ids.add(value);return value}
  const nodes=[]
  for(const node of document.graph.nodes) {
    const module=library.modules?.[node.module]
    if(node.kind!=='SEGMENT' || node.assembly || !module || module.depth!==1 || module.cells.some(c=>c.pos.z!==0 || c.flexible)) {nodes.push(node);continue}
    const sources=[], keys=new Map(), cells=[]
    for(const cell of module.cells) {
      if(cell.empty || cell.block==='minecraft:air') continue
      const key=JSON.stringify([cell.form,cell.block,cell.role,cell.surfaces,cell.rotations])
      if(!keys.has(key)) {
        const form=library.forms?.[cell.form], sourceId=id()
        keys.set(key,sources.length)
        sources.push({...newNode(true,'COPYCAT',sourceId,(node.x||0)-520,(node.y||0)+sources.length*180),
          name:(form?.name || cell.form || cell.block || 'Grundform').split('/').at(-1).trim().slice(0,64),
          form:cell.form,block:cell.block || '',role:cell.role || 'wall',surfaces:clone(cell.surfaces || null)})
      }
      cells.push({x:cell.pos.x,y:cell.pos.y,sourceIndex:keys.get(key),turn:cell.turn || 0})
    }
    const layout={...newNode(true,'SEGMENT_LAYOUT',id(),(node.x||0)-260,node.y||0),name:'Bauteilanordnung',
      length:module.width,height:module.height,depth:1,layoutCells:cells}
    nodes.push(...sources,layout,{...node,assembly:true,module:'',layout:layout.id,inputs:sources.map(n=>n.id),length:module.width,height:module.height,depth:1})
  }
  return {...document,graph:{...document.graph,nodes}}
}

export function copyReferenceOption(option) {
  if(!option?.document) throw new Error('Zuerst eine Quelle auswählen.')
  if(option.referenceOption) {
    const expanded=expandReferences(clone(option.document),clone(option.library || {forms:{},modules:{}}))
    const document=unpackModules(expanded.document,expanded.library)
    document.name=copyName(option.name);delete document.id;delete document.version;delete document.referenceSchema
    document.editableOutput=option.output
    readGraph(document,'building')
    return {mode:'building',document,library:expanded.library,materials:{},selected:option.output}
  }
  const source=readGraph(clone(option.document),'form')
  const document=editableForm(source)
  document.name=copyName(option.name || document.name);delete document.id;document.version=1
  const output=document.graph.nodes.find(n=>n.kind==='OUTPUT')
  output.previewGroup=''
  readGraph(document,'form')
  return {mode:'form',document,library:{forms:{},modules:{}},materials:clone(option.materials || {}),selected:output.id}
}

export function copyNodeSource({mode,document,library,materials,nodeId}) {
  const node=document.graph.nodes.find(n=>n.id===nodeId)
  if(!node) throw new Error('Der Quellknoten ist nicht mehr vorhanden.')
  if(node.kind==='REFERENCE') {
    const r=node.reference
    const copy=copyReferenceOption({referenceOption:true,name:node.name,document:r.document,library:r.library,output:r.output})
    const output=copy.document.graph.nodes.find(n=>n.id===r.output)
    if(node.referenceOverrides && output?.kind==='COPYCAT') Object.assign(output,clone(node.referenceOverrides))
    return copy
  }
  if(mode==='building' && node.kind==='COPYCAT') {
    const form=library.forms?.[node.form]
    if(!form) throw new Error('Für diese Quelle ist kein Formgraph gespeichert.')
    const palette=normalizeDocument(true,expandReferences(document,library).document).palette
    const surfaces=Object.fromEntries(formGroups(form).map(g=>[g.id,node.surfaces?.[g.id] || palette[g.role==='surface'?node.role || 'wall':g.role] || 'minecraft:stone']))
    return copyReferenceOption({name:node.name || form.name,document:form,materials:surfaces})
  }
  if(mode==='form' && node.kind==='SAVED') {
    const source=node.editableGraph || {schema:3,name:node.savedCopycat.name,groups:clone(formGroups(document)),graph:{schema:2,nodes:[clone(node),{...newNode(false,'OUTPUT','copy_result',500,0),inputs:[node.id]}]}}
    return copyReferenceOption({name:source.name,document:source,materials:node.editableMaterials || materials})
  }
  throw new Error('Für diesen Knoten ist keine gespeicherte Quelle vorhanden.')
}
