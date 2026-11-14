import { compileForm, mountSource, newNode, previewGraph } from './generated/engine.mjs'

const clone = value => structuredClone(value)
const axes = ['x','y','z']
const orders = [[0,1,2],[0,2,1],[1,0,2],[1,2,0],[2,0,1],[2,1,0]]
const index = (x,y,z) => x+256*y+16*z

// Recover editable rectilinear constructions from the kit's voxel profiles.
// The existing recipes store their finished masks, rather than a construction history.
function boxesFor(mask) {
  const bytes = Uint8Array.from(atob(mask), c => c.charCodeAt(0))
  const original = new Set(Array.from({length:4096},(_,i)=>i).filter(i=>bytes[i>>3] & 1<<(i&7)))
  const variants = orders.map(order => {
    const pending = new Set(original), boxes=[]
    while(pending.size) {
      const first=pending.values().next().value
      const lo=[first%16,Math.floor(first/256),Math.floor(first/16)%16], hi=lo.map(n=>n+1)
      for(const axis of order) {
        while(hi[axis]<16) {
          const end=[...hi];end[axis]++
          let fits=true
          for(let y=lo[1];fits && y<end[1];y++) for(let z=lo[2];fits && z<end[2];z++) for(let x=lo[0];x<end[0];x++)
            if(!pending.has(index(x,y,z))) {fits=false;break}
          if(!fits) break
          hi[axis]++
        }
      }
      for(let y=lo[1];y<hi[1];y++) for(let z=lo[2];z<hi[2];z++) for(let x=lo[0];x<hi[0];x++) pending.delete(index(x,y,z))
      boxes.push([...lo,...hi])
    }
    return boxes
  })
  return variants.reduce((a,b)=>a.length<=b.length?a:b)
}

function builder(groups) {
  const nodes=[], cache=new Map(), group=groups[0].id
  const add=(kind,fields,key)=>{
    if(cache.has(key)) return cache.get(key)
    const id=`recipe_${nodes.length}`
    nodes.push({...newNode(false,kind,id,0,0),...fields});cache.set(key,id);return id
  }
  const operation=(kind,inputs)=>inputs.length===1?inputs[0]:add(kind,{inputs},`${kind}:${inputs.join(',')}`)
  const union=items=>{
    let current=[...new Set(items)]
    while(current.length>16) {const next=[];for(let i=0;i<current.length;i+=16) next.push(operation('UNION',current.slice(i,i+16)));current=next}
    return operation('UNION',current)
  }
  const full=material=>add('INPUT',{editorLabel:`Vollblock · ${groups.find(g=>g.id===material)?.name || material}`,part:{...newNode(false,'INPUT','x',0,0).part,group:material}},`full:${material}`)
  const primitive=(axis,lo,length)=>{
    if(length===16) return full(group)
    let part={source:length===3?'create:copycat_panel':'copycats:copycat_layer',size:length===3?1:length/2,group}
    part=mountSource(part,['east','up','south'][axis])
    // Rotating a layer may anchor it at the opposite edge of the block.
    const starts=[]
    for(const x of [0,15]) for(const y of [0,length-1]) for(const z of [0,15]) {
      let p=[x,part.top?15-y:y,z]
      for(let n=0;n<part.spin;n++) p=[15-p[2],p[1],p[0]]
      for(let n=0;n<part.tilt;n++) p=[p[0],15-p[2],p[1]]
      for(let n=0;n<part.turn;n++) p=[15-p[2],p[1],p[0]]
      starts.push(p[axis])
    }
    part.offset={x:0,y:0,z:0,[axes[axis]]:lo-Math.min(...starts)}
    return add('INPUT',{part,editorLabel:`${length===3?'Panel':'Schicht'} ${axes[axis].toUpperCase()} · ${lo}–${lo+length} hB`},`strip:${axis}:${lo}:${length}`)
  }
  const strip=(axis,lo,hi)=>{
    const width=hi-lo
    if(width%2===0 || width===3) return primitive(axis,lo,width)
    if(width===1) {
      if(lo===0) return operation('DIFFERENCE',[primitive(axis,0,3),primitive(axis,1,2)])
      if(hi===16) return operation('DIFFERENCE',[primitive(axis,13,3),primitive(axis,13,2)])
      return operation('INTERSECTION',[primitive(axis,lo-1,2),primitive(axis,lo,2)])
    }
    if(hi+3<=16) return operation('DIFFERENCE',[primitive(axis,lo,width+3),primitive(axis,hi,3)])
    if(lo>=3) return operation('DIFFERENCE',[primitive(axis,lo-3,width+3),primitive(axis,lo-3,3)])
    if(lo>0 && hi<16) return operation('INTERSECTION',[primitive(axis,lo-1,width+1),primitive(axis,lo,width+1)])
    return operation('DIFFERENCE',[full(group),strip(axis,lo===0?hi:0,lo===0?16:lo)])
  }
  return {nodes,union,box:({bounds:b,group:g})=>operation('INTERSECTION',[full(g),...axes.flatMap((_,a)=>b[a]===0 && b[a+3]===16?[]:[strip(a,b[a],b[a+3])])])}
}

function arrange(nodes) {
  const depth=new Map(), rows=new Map()
  return nodes.map((n,i)=>{
    const layer=n.inputs?.length?1+Math.max(...n.inputs.filter(Boolean).map(id=>depth.get(id)||0)):0
    depth.set(n.id,layer)
    const row=rows.get(layer) || 0;rows.set(layer,row+1)
    return {...n,x:Math.min(7800,layer*240),y:-300+row*170}
  })
}

function recipe(groups,items,name) {
  const b=builder(groups), output=b.union(items.map(b.box))
  let nodes=[...b.nodes,newNode(false,'OUTPUT','result',0,0)]
  nodes.at(-1).inputs=[output]
  if(nodes.length>64 || nodes.filter(n=>n.kind==='INPUT').length>32) {
    const middle=Math.ceil(items.length/2)
    if(middle===items.length) throw new Error('Dieses Profil kann nicht in die verfügbaren Grundformknoten zerlegt werden.')
    const children=[items.slice(0,middle),items.slice(middle)].map((part,i)=>recipe(groups,part,`${name.slice(0,43)} · Teil ${i+1}`))
    nodes=children.map((document,i)=>({...newNode(false,'SAVED',`part_${i}`,0,0),
      editorLabel:document.name,savedCopycat:{id:'',version:1,name:document.name,parts:compileForm(document)},editableGraph:document}))
    nodes.push({...newNode(false,'UNION','combine',0,0),inputs:nodes.map(n=>n.id)}, {...newNode(false,'OUTPUT','result',0,0),inputs:['combine']})
  }
  return {schema:3,name:name.slice(0,64),groups:clone(groups),graph:{schema:2,nodes:arrange(nodes)}}
}

export function editableForm(document) {
  const copy=clone(document), nodes=copy.graph?.nodes
  // Preserve real authoring graphs; only reconstruct the baked mask-only profile.
  const saved=nodes?.filter(n=>n.kind==='SAVED') || []
  if(nodes?.length!==2 || saved.length!==1 || nodes.find(n=>n.kind==='OUTPUT')?.inputs?.[0]!==saved[0].id) return copy
  const parts=saved[0].savedCopycat.parts
  if(!parts.length || parts.some(p=>!p.cutMask || p.exact || p.exactClip)) return copy
  const items=parts.flatMap(part=>boxesFor(part.cutMask).map(bounds=>({bounds,group:part.group})))
  const result=recipe(copy.groups || [{id:'body',name:'Body',role:'surface',priority:0}],items,copy.name)
  if(saved[0].quarterTurns) {
    const output=result.graph.nodes.find(n=>n.kind==='OUTPUT')
    const rotation={...newNode(false,'ROTATE','source_rotation',0,0),inputs:[output.inputs[0],''],rotationAxis:saved[0].rotationAxis || 'NORMAL',quarterTurns:saved[0].quarterTurns}
    result.graph.nodes.splice(result.graph.nodes.length-1,0,rotation);output.inputs=['source_rotation']
    result.graph.nodes=arrange(result.graph.nodes)
  }
  // Reject an unexpected volume change before opening the copy. Per-voxel material
  // ownership and the recursive construction tree are covered by the kit tests.
  const before=previewGraph(false,copy,{},''), after=previewGraph(false,result,{},'')
  if(before.count!==after.count) throw new Error('Das rekonstruierte Profil stimmt nicht mit der Vorlage überein.')
  return result
}
