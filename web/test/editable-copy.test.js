import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { newNode, previewGraph } from '../src/generated/engine.mjs'
import { copyReferenceOption, copyNodeSource } from '../src/editable-copy.js'
import { listReferenceOptions, prepareReferenceOption, styleLibrary } from '../src/reference-options.js'
import { loadFormCopycatWithMaterials, loadBuildingCopycat } from '../src/copycat-library.js'
import { inspect, readGraph, writeGraph } from '../src/model.js'

const voxels=plan=>{
  const occupied=new Map()
  for(const mesh of plan.meshes) for(const [px,py,pz] of mesh.positions) for(const [x,y,z,X,Y,Z] of mesh.boxes)
    for(let a=x;a<X;a++) for(let b=y;b<Y;b++) for(let c=z;c<Z;c++) occupied.set(`${px*16+a},${py*16+b},${pz*16+c}`,mesh.material)
  return [...occupied].sort(([a],[b])=>a.localeCompare(b))
}
function verifyRecipe(document) {
  assert.ok(document.graph.nodes.length<=64)
  assert.ok(document.graph.nodes.filter(n=>['INPUT','SAVED'].includes(n.kind)).length<=32)
  inspect('form',document)
  for(const node of document.graph.nodes) {
    if(node.kind==='INPUT') {
      assert.equal(node.part.cutMask,'');assert.equal(node.part.exact,null);assert.equal(node.part.exactClip,null)
    }
    if(node.kind==='SAVED') {assert.ok(node.editableGraph);verifyRecipe(node.editableGraph)}
  }
}

test('all 32 Renaissance parts open as editable basic-shape constructions with identical voxels and ownership',()=>{
  const before=JSON.stringify(styleLibrary)
  for(const option of listReferenceOptions().filter(o=>o.id.startsWith('style-form:'))) {
    const copy=copyReferenceOption(option)
    assert.equal(copy.mode,'form');assert.ok(copy.document.name.endsWith('· Kopie'))
    assert.equal(copy.document.id,undefined)
    verifyRecipe(copy.document)
    assert.deepEqual(voxels(previewGraph(false,copy.document,{},'')),voxels(previewGraph(false,option.document,{},'')),option.name)
    const round=readGraph(JSON.parse(JSON.stringify(writeGraph('form',copy.document))),'form')
    assert.deepEqual(voxels(previewGraph(false,round,{},'')),voxels(previewGraph(false,option.document,{},'')))
    verifyRecipe(round)
  }
  assert.equal(JSON.stringify(styleLibrary),before)
})

test('all complete templates open as independent source/layout/segment graphs with identical placement materials',()=>{
  const before=JSON.stringify(styleLibrary)
  for(const raw of listReferenceOptions().filter(o=>o.kind==='template')) {
    const option=prepareReferenceOption(raw),copy=copyReferenceOption(option)
    assert.equal(copy.mode,'building')
    const segment=copy.document.graph.nodes.find(n=>n.id===copy.selected)
    assert.equal(segment.kind,'SEGMENT');assert.equal(segment.assembly,true);assert.equal(segment.module,'')
    assert.ok(copy.document.graph.nodes.some(n=>n.kind==='SEGMENT_LAYOUT'))
    assert.ok(copy.document.graph.nodes.some(n=>n.kind==='COPYCAT'))
    assert.deepEqual(voxels(previewGraph(true,copy.document,copy.library,copy.selected)),voxels(previewGraph(true,option.document,option.library,option.output)),option.name)
    copy.library.forms[Object.keys(copy.library.forms)[0]].name='Modified'
  }
  assert.equal(JSON.stringify(styleLibrary),before)
})

test('editing a copied source changes its own graph and can be applied to the copied building only',()=>{
  const option=prepareReferenceOption(listReferenceOptions().find(o=>o.kind==='template' && o.name.includes('Kreuzstockfenster')))
  const building=copyReferenceOption(option), source=building.document.graph.nodes.find(n=>n.kind==='COPYCAT')
  const original=JSON.stringify(building)
  const copy=copyNodeSource({...building,nodeId:source.id})
  assert.equal(copy.mode,'form');assert.ok(copy.document.graph.nodes.some(n=>n.kind==='INPUT'))
  const layer=copy.document.graph.nodes.find(n=>n.kind==='INPUT' && n.part.source==='copycats:copycat_layer' && n.part.size>1 && n.part.size<8)
  layer.part.size--
  copy.materials[copy.document.groups[0].id]='minecraft:gold_block'
  assert.equal(JSON.stringify(building),original)
  const applied=loadBuildingCopycat(building.document,building.library,{id:'own',name:copy.document.name,document:copy.document,materials:copy.materials},source.id)
  assert.notDeepEqual(voxels(previewGraph(true,applied.document,applied.library,building.selected)),voxels(previewGraph(true,building.document,building.library,building.selected)))
  assert.deepEqual(applied.library.forms[source.form],building.library.forms[source.form])
  assert.notEqual(copy.document.name,building.library.forms[source.form].name)
})

test('edited nested recipe graphs survive applying, saving and reopening a saved source',()=>{
  const raw=listReferenceOptions().find(o=>o.id==='style-form:dr_ornament@1')
  const copy=copyReferenceOption(raw),node=copy.document.graph.nodes.find(n=>n.kind==='SAVED')
  const child=copyNodeSource({...copy,nodeId:node.id})
  child.document.name='Edited child'
  child.materials[child.document.groups[0].id]='minecraft:gold_block'
  const applied=loadFormCopycatWithMaterials(copy.document,{id:'child',document:child.document,materials:child.materials,name:child.document.name},node.id,copy.materials)
  const round=readGraph(JSON.parse(JSON.stringify(writeGraph('form',applied.document))),'form')
  const reopened=copyNodeSource({mode:'form',document:round,library:{},materials:applied.materials,nodeId:node.id})
  assert.equal(reopened.document.name,'Edited child · Kopie')
  assert.equal(reopened.materials[child.document.groups[0].id],'minecraft:gold_block')
  verifyRecipe(reopened.document)
})

test('real authoring graphs keep their operators and only the copy loses library identity',()=>{
  const document=JSON.parse(readFileSync(new URL('../../examples/nodegraph-form.json',import.meta.url)))
  document.id='original';document.version=7
  const copy=copyReferenceOption({name:'Original',document,materials:{body:'minecraft:stone'}})
  assert.deepEqual(copy.document.graph.nodes.map(n=>n.kind),document.graph.nodes.map(n=>n.kind))
  assert.equal(copy.document.id,undefined);assert.equal(copy.document.version,1)
  copy.document.graph.nodes[0].part.source='copycats:copycat_slab'
  assert.notEqual(copy.document.graph.nodes[0].part.source,document.graph.nodes[0].part.source)
})


test('rotated baked profiles reconstruct with the same visible orientation and remain editable',()=>{
 const source=structuredClone(styleLibrary.forms['dr_jamb_left@1'])
 source.graph.nodes.find(n=>n.kind==='SAVED').quarterTurns=1
 source.graph.nodes.find(n=>n.kind==='SAVED').rotationAxis='Y'
 const copy=copyReferenceOption({name:source.name,document:source})
 assert.ok(copy.document.graph.nodes.some(n=>n.kind==='ROTATE'))
 verifyRecipe(copy.document)
 assert.deepEqual(voxels(previewGraph(false,copy.document,{},'')),voxels(previewGraph(false,source,{},'')))
})


test('saving an edited template keeps its segment output for later reference instancing',()=>{
 const option=prepareReferenceOption(listReferenceOptions().find(o=>o.kind==='template'))
 const copy=copyReferenceOption(option)
 const saved={id:'own-template',mode:'building',name:copy.document.name,document:copy.document,library:copy.library}
 const raw=listReferenceOptions({savedGraphs:[saved]}).find(o=>o.id==='graph:own-template')
 const reopened=prepareReferenceOption(raw)
 assert.equal(reopened.output,copy.selected)
 assert.equal(reopened.type,'WALL')
 assert.deepEqual(voxels(previewGraph(true,reopened.document,reopened.library,reopened.output)),voxels(previewGraph(true,copy.document,copy.library,copy.selected)))
})
