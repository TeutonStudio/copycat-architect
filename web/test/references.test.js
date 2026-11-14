import test from 'node:test'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import {starterBuilding, newNode, previewGraph, editNativeGroup} from '../src/generated/engine.mjs'
import {applyReference, expandReferences, previewReference, foldReferenceEdit, copycatFromReference} from '../src/references.js'
import {listReferenceOptions, prepareReferenceOption} from '../src/reference-options.js'
import {applyWires, connections, editNode, inspect, readGraph, writeGraph} from '../src/model.js'
import {insertNodeGroup, saveNodeGroup} from '../src/node-groups.js'
const fixture = name => JSON.parse(readFileSync(new URL(`../../examples/${name}.json`, import.meta.url)))
const library=fixture('library')
const template = () => prepareReferenceOption(listReferenceOptions({library}).find(o=>o.kind==='template'))
const instance = (option=template(),document=starterBuilding()) => applyReference({document,library,option})
const plan = (document,id='') => {const r=expandReferences(document,library);return previewGraph(true,r.document,r.library,id)}

test('every complete Renaissance template produces multi-block geometry under the named folder',()=>{
 const options=listReferenceOptions({library}).filter(o=>o.kind==='template')
 assert.equal(options.length,12)
 for(const raw of options) {
  assert.equal(raw.path[0],'deutsche Renaisance')
  const option=prepareReferenceOption(raw), r=instance(option)
  assert.ok(plan(r.document,r.nodeId).count>1,option.name)
  assert.equal(inspect('building',r.document).find(e=>e.node.id===r.nodeId).outputType,'WALL')
  assert.equal(r.document.graph.nodes.length,starterBuilding().graph.nodes.length+1)
 }
})

test('source definitions and their libraries are pinned and survive JSON roundtrip',()=>{
 const option=template(),r=instance(option), before=plan(r.document,r.nodeId)
 option.library=structuredClone(option.library);option.document=structuredClone(option.document)
 option.document.graph.nodes=[];option.library.forms={}
 const reopened=readGraph(JSON.parse(JSON.stringify(writeGraph('building',r.document))),'building')
 assert.deepEqual(plan(reopened,r.nodeId),before)
 assert.equal(reopened.graph.nodes.at(-1).reference.version,1)
})

test('saved whole graphs and individual typed outputs can be referenced',()=>{
 const source=fixture('building-graph-assembly')
 const raw=listReferenceOptions({savedGraphs:[{id:'one',mode:'building',name:'Haus',version:3,document:source,library}]}).find(o=>o.kind==='graph')
 for (const output of ['building','wall_copycat','wall']) {
  const option=prepareReferenceOption(raw,output),r=instance(option)
  assert.ok(plan(r.document,r.nodeId).count>0)
  assert.deepEqual(plan(r.document,r.nodeId),previewReference(option))
  assert.equal(r.document.graph.nodes.at(-1).reference.version,3)
 }
})

test('saved groups and cards keep internal wires, and form groups become reusable forms',()=>{
 for (const [mode,file,ids] of [['building','building-graph-assembly',['wall_copycat','wall']],['form','nodegraph-form',['outer']]]) {
  const source=fixture(file),selected=mode==='form'?[source.graph.nodes.find(n=>n.kind==='INPUT').id]:ids
  const group=saveNodeGroup(mode,source,selected,'Karte',{},library)
  const raw=listReferenceOptions({savedGroups:[group]}).find(o=>o.kind==='group')
  const option=prepareReferenceOption(raw)
  if(mode==='building') {const r=instance(option);assert.ok(plan(r.document,r.nodeId).count>0)}
  else assert.equal(option.kind,'saved')
 }
})

test('references wire as typed segments, reject wrong types, edit neighbours and delete cleanly',()=>{
 const source=fixture('building-graph-assembly')
 const option=prepareReferenceOption(listReferenceOptions({savedGraphs:[{id:'wall',name:'Wall',mode:'building',document:source,library}]}).find(o=>o.kind==='graph'),'wall')
 const r=instance(option), id=r.nodeId
 const before=connections(inspect('building',r.document))
 const linked=applyWires('building',r.document,inspect('building',r.document),[...before.filter(w=>!(w.target==='room' && w.port.startsWith('wall:'))),{source:id,target:'room',port:'wall:0:0:0'}])
 assert.ok(connections(inspect('building',linked)).some(w=>w.source===id))
 assert.ok(plan(linked).count>0)
 assert.throws(()=>applyWires('building',linked,inspect('building',linked),[...before,{source:id,target:'wall',port:'in:0'}]))
 const node=linked.graph.nodes.find(n=>n.id==='room')
 const edited=editNode('building',linked,{...node,name:'Changed'})
 assert.equal(edited.graph.nodes.find(n=>n.id==='room').name,'Changed')
 const deleted=applyWires('building',linked,inspect('building',linked),before, [id])
 assert.equal(deleted.graph.nodes.some(n=>n.kind==='REFERENCE'),false)
 assert.ok(plan(deleted).count>0)
})

test('exposed group inputs remap to internal ports without exposing members',()=>{
 const source=fixture('building-graph-assembly')
 const group=saveNodeGroup('building',source,['wall'],'Offene Wand',{},library)
 const option=prepareReferenceOption(listReferenceOptions({savedGroups:[group]}).find(o=>o.kind==='group'))
 const r=instance(option),entry=inspect('building',r.document).find(e=>e.node.id===r.nodeId)
 const port=entry.ports.find(p=>p.accepts.includes('COPYCAT'))
 assert.ok(port)
 const wires=[...connections(inspect('building',r.document)),{source:'wall_copycat',target:r.nodeId,port:port.id}]
 const linked=applyWires('building',r.document,inspect('building',r.document),wires)
 assert.equal(linked.graph.nodes.at(-1).referenceInputs[port.id],'wall_copycat')
 assert.ok(plan(linked,r.nodeId).count>0)
 const copy=insertNodeGroup(linked,saveNodeGroup('building',linked,[r.nodeId],'Ref',{},library),()=> 'another').document
 assert.ok(inspect('building',copy).find(e=>e.node.id==='another'))
})

test('nested references preserve geometry, cyclic definitions fail with a readable error',()=>{
 const first=instance(),second=instance({name:'Nested',identity:'nested',version:1,document:first.document,library,output:first.nodeId})
 assert.deepEqual(plan(first.document,first.nodeId),plan(second.document,second.nodeId))
 const bad=structuredClone(first.document),definition=bad.graph.nodes.at(-1).reference
 definition.document=structuredClone(first.document)
 assert.throws(()=>expandReferences(bad,library),/Rekursive Referenz/)
})


test('native group and projection edits preserve opaque references and their geometry',()=>{
 const r=instance(),resolved=expandReferences(r.document,library)
 const graph=editNativeGroup(true,resolved.document,'create','','Wall sources',['wall_copycat','plate_copycat'])
 const edited=foldReferenceEdit(r.document,resolved,{...resolved.document,graph})
 assert.equal(edited.graph.nodeGroups.length,1)
 assert.equal(edited.graph.nodes.at(-1).kind,'REFERENCE')
 assert.deepEqual(plan(edited,r.nodeId),plan(r.document,r.nodeId))
 const expanded=expandReferences(edited,library)
 const duplicated=editNativeGroup(true,expanded.document,'instantiate',edited.graph.nodeGroups[0].id,'',[])
 const folded=foldReferenceEdit(edited,expanded,{...expanded.document,graph:duplicated})
 assert.equal(folded.graph.nodeGroups.length,2)
 assert.equal(folded.graph.nodes.filter(n=>n.kind==='REFERENCE').length,1)
 assert.ok(inspect('building',folded))
})


test('legacy segment references retain Copycat geometry instead of becoming native blocks',()=>{
 const source=starterBuilding()
 source.graph.nodes.push({...newNode(true,'SEGMENT','legacy',100,100),assembly:false,module:'',form:'full@1',block:'',length:3,height:2,depth:1})
 const option={document:source,library,output:'legacy',identity:'legacy',name:'Legacy'}
 const r=instance(option),resolved=expandReferences(r.document,library)
 assert.equal(resolved.document.graph.nodes.find(n=>n.id===r.nodeId).block,'')
 assert.deepEqual(plan(r.document,r.nodeId),previewReference(option))
})


test('source replacement preserves connected reference inputs and rejects incompatible sockets atomically',()=>{
 const source=fixture('building-graph-assembly')
 const group=saveNodeGroup('building',source,['wall'],'Wall',{},library)
 const option=prepareReferenceOption(listReferenceOptions({savedGroups:[group]}).find(o=>o.kind==='group'))
 const first=instance(option),entry=inspect('building',first.document).find(e=>e.node.id===first.nodeId)
 const port=entry.ports.find(p=>p.accepts.includes('COPYCAT'))
 const linked=applyWires('building',first.document,inspect('building',first.document),[...connections(inspect('building',first.document)),{source:'wall_copycat',target:first.nodeId,port:port.id}])
 const before=JSON.stringify(linked)
 const reopened=prepareReferenceOption(listReferenceOptions({savedGroups:[structuredClone(group)]}).find(o=>o.kind==='group'))
 const changed=applyReference({document:linked,library,nodeId:first.nodeId,option:reopened})
 assert.deepEqual(changed.document.graph.nodes.at(-1).referenceInputs,linked.graph.nodes.at(-1).referenceInputs)
 assert.throws(()=>applyReference({document:linked,library,nodeId:first.nodeId,option:template()}),/belegte Eingang/)
 assert.throws(()=>copycatFromReference(linked.graph.nodes.at(-1)),/belegter Eingang/)
 assert.equal(JSON.stringify(linked),before)
})

test('Copycat instance rotation and surface overrides survive a compatible source replacement',()=>{
 const source=starterBuilding()
 source.graph.nodes.find(n=>n.id==='wall_copycat').quarterTurns=1
 source.graph.nodes.find(n=>n.id==='wall_copycat').surfaces={body:'minecraft:gold_block'}
 const option={name:'Form',identity:'form',document:starterBuilding(),library,output:'wall_copycat'}
 const r=applyReference({document:source,library,nodeId:'wall_copycat',option})
 const node=expandReferences(r.document,library).document.graph.nodes.find(n=>n.id==='wall_copycat')
 assert.equal(node.quarterTurns,1);assert.equal(node.surfaces.body,'minecraft:gold_block')
 assert.equal(copycatFromReference(r.document.graph.nodes.find(n=>n.id===r.nodeId)).quarterTurns,1)
 assert.throws(()=>applyReference({document:r.document,library,nodeId:r.nodeId,option:template()}),/Instanzeinstellungen/)
})
