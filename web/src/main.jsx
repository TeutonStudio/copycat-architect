import { lazy, memo, Suspense, useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Background, Controls, Handle, MiniMap, Position, ReactFlow, useUpdateNodeInternals } from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { adaptRectangles, newNode, starterBuilding } from './generated/engine.mjs'
import { applyWires, connections, formGroups, inspect, editNode, model, position, protectedNode, readGraph, replaceNodes, validateLibrary, writeGraph } from './model.js'
import { NativeDocumentFields } from './NativeFields.jsx'
import { DocumentFields, Field, names, NodeFields, portName } from './Inspector.jsx'
import './style.css'
import { SplitHandle } from './SplitHandle.jsx'
import { Workspace, useWorkspace } from './Workspace.jsx'
import { loadBuildingCopycat, loadFormCopycatWithMaterials } from './copycat-library.js'
import { applyCopycatOption } from './copycat-selection.js'
import { insertNodeGroup, mergeLibraries, saveNodeGroup } from './node-groups.js'
import { applyReference, hasReferences, expandReferences, foldReferenceEdit, instanceOverrides, copycatFromReference } from './references.js'
import { PagedPanel } from './PagedPanel.jsx'
import baseLibrary from '../../examples/library.json'
import formExample from '../../examples/nodegraph-form.json'
import formRotation from '../../examples/nodegraph-rotation-xor.json'
import formMaterials from '../../examples/nodegraph-material-groups.json'
import buildingExample from '../../examples/building-graph.json'
import buildingAssembly from '../../examples/building-graph-assembly.json'
import buildingBasements from '../../examples/building-graph-basements.json'
import buildingRotationSegment from '../../examples/building-rotation-segment.json'
import buildingRoofPeak from '../../examples/building-roof-peak.json'
import buildingRoofEave from '../../examples/building-roof-eave.json'

const Preview = lazy(() => import('./Preview.jsx'))
const CopycatPicker = lazy(() => import('./CopycatPicker.jsx'))
const wiki = 'https://github.com/TeutonStudio/copycat-architect/wiki'
const examples = {
  form: [
    ['Aussparung', formExample], ['Rotation und Differenz', formRotation], ['Materialgruppen', formMaterials]
  ],
  building: [
    ['Gebäude aus Copycats', buildingAssembly], ['Rotationssegment · Säule', buildingRotationSegment], ['Dachspitze bei ungerader Breite', buildingRoofPeak], ['Dachendkante parallel zum First', buildingRoofEave], ['L-förmiges Gebäude', buildingExample], ['Gebäude mit Kellern', buildingBasements]
  ]
}
const query = new URLSearchParams(window.location.search)
const initialMode = query.get('editor') === 'form' || query.get('graph') === 'copycat' ? 'form' : 'building'

function DiagramNode({ id, data: { node, ports, groupName, outputType, hasOutput }, selected }) {
  const updateInternals = useUpdateNodeInternals()
  const signature = ports.map(port => port.id).join('|')
  useEffect(() => updateInternals(id), [id, signature, updateInternals])
  const source = hasOutput ?? !['BUILDING', 'OUTPUT'].includes(node.kind)
  return <div className={`diagram-node ${selected ? 'selected' : ''} ${source ? '' : 'result-node'}`}>
    <div className="node-heading">
      <span className="node-symbol">{source ? '◇' : '◆'}</span>
      <div><strong>{node.name || names[node.kind]}</strong><small>{node.name ? names[node.kind] : node.id}{groupName ? ` · ${groupName}` : ''}</small></div>
      {source && <Handle type="source" position={Position.Right} id="out" aria-label={`Ausgang · ${outputType}`} title={`Ausgang · ${outputType}`} />}
    </div>
    {ports.length > 0 && <div className="ports">{ports.map(port => <div className="port" key={port.id}>
      <Handle type="target" position={Position.Left} id={port.id} title={`${portName(port, node)} · ${(port.types || []).join(" / ")}`} />
      <span>{portName(port, node)}</span>
      <span className={`port-status ${port.source ? 'connected' : ''}`}>{port.source ? '●' : '○'}</span>
    </div>)}</div>}
  </div>
}

const nodeTypes = { architect: memo(DiagramNode) }
const edgeStyle = { stroke: '#8daeb1', strokeWidth: 1.7 }

function useHistory(initial) {
  const [state, setState] = useState(() => ({ current: initial, past: [], future: [] }))
  const push = next => setState(({ current, past }) => ({
    current: next, past: [...past.slice(-49), current], future: []
  }))
  const undo = () => setState(state => state.past.length ? {
    current: state.past.at(-1), past: state.past.slice(0, -1), future: [state.current, ...state.future]
  } : state)
  const redo = () => setState(state => state.future.length ? {
    current: state.future[0], past: [...state.past, state.current], future: state.future.slice(1)
  } : state)
  return { ...state, push, undo, redo }
}

function downloadJson(document, mode) {
  const name = (document.name ?? 'copycat').normalize('NFKD').replace(/[^a-zA-Z0-9_-]+/g, '-').replace(/^-|-$/g, '') || 'copycat'
  const url = URL.createObjectURL(new Blob([JSON.stringify(document) + '\n'], { type: 'application/json' }))
  const link = window.document.createElement('a')
  link.href = url
  link.download = `${name}-${mode}.json`
  link.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}

function Studio({ mode, active }) {
  const { data: savedData, update: updateWorkspace } = useWorkspace()
  const draft = savedData.drafts[mode]
  const history = useHistory(draft?.document ?? examples[mode][0][1])
  const document = history.current
  const [library, setLibrary] = useState(draft?.library ?? baseLibrary)
  const [materials, setMaterials] = useState(draft?.materials ?? {})
  // A source change also changes material mappings and library snapshots.
  const historyContext = useRef(new WeakMap())
  const [selectedIds, setSelectedIds] = useState([])
  const onSelectionChange = useCallback(({ nodes }) => setSelectedIds(previous => {
    const next = nodes.map(node => node.id)
    return next.length === previous.length && next.every(id => previous.includes(id)) ? previous : next
  }), [])
  const [saveName, setSaveName] = useState('')
  useEffect(() => {
    updateWorkspace(data => {
      const previous = data.drafts[mode]
      return previous?.document === document && previous?.library === library && previous?.materials === materials ? data
        : { ...data, drafts: { ...data.drafts, [mode]: { mode, document, library, materials } } }
    })
  }, [mode, document, library, materials])
  const [selected, setSelected] = useState(null)
  const [selectedWire, setSelectedWire] = useState(null)
  const [dragging, setDragging] = useState(null)
  const [message, setMessage] = useState(null)
  const [flow, setFlow] = useState(null)
  const [kind, setKind] = useState('')
  const [pane, setPane] = useState('graph')
  const [workspace, setWorkspace] = useState(0)
  // React Flow measurements belong to the view, never to the Minecraft document.
  const [nodeMeasurements, setNodeMeasurements] = useState({})
  const [graphShare, setGraphShare] = useState(68)
  const [previewShare, setPreviewShare] = useState(45)
  const [previewCollapsed, setPreviewCollapsed] = useState(false)
  const [inspectorCollapsed, setInspectorCollapsed] = useState(false)
  const [previewMode, setPreviewMode] = useState('graph')
  const [copycatPicker, setCopycatPicker] = useState(null)
  const graphInput = useRef(null)
  const libraryInput = useRef(null)
  const copycatInput = useRef(null)
  const canvas = useRef(null)
  const entries = useMemo(() => inspect(mode, document), [mode, document])
  const resolved = useMemo(() => hasReferences(document) ? expandReferences(document,library) : null,[document,library])
  const wires = useMemo(() => connections(entries), [entries])
  const current = entries.find(({ node }) => node.id === selected)
  const resultNode = entries.find(({ node }) => ['OUTPUT', 'BUILDING'].includes(node.kind) && !document.graph.nodeGroups?.some(group => group.nodes.includes(node.id)))
  const visibleNode = current ?? resultNode
  const kinds = mode === 'form' ? model.form.map(type => type.kind).filter(kind => kind !== 'OUTPUT') : model.building.filter(kind => kind !== 'BUILDING')
  const edgeId = ({ target, port }) => `${target}/${port}`
  const nodes = useMemo(() => entries.map(data => ({
    id: data.node.id, type: 'architect', data: { ...data, hasOutput: data.node.id !== resultNode.node.id, groupName: document.graph.nodeGroups?.find(group => group.nodes.includes(data.node.id))?.name },
    position: dragging?.id === data.node.id ? dragging.position : { x: data.node.x, y: data.node.y },
    selected: selectedIds.includes(data.node.id),
    measured: nodeMeasurements[data.node.id]
  })), [entries, dragging, selectedIds, nodeMeasurements])
  const onNodesChange = useCallback(changes => {
    const measured = changes.filter(change => change.type === 'dimensions' && change.dimensions)
    if (!measured.length) return
    setNodeMeasurements(previous => {
      const ids = new Set(entries.map(({ node }) => node.id))
      let changed = false
      const next = Object.fromEntries(Object.entries(previous).filter(([id]) => ids.has(id)))
      changed = Object.keys(next).length !== Object.keys(previous).length
      for (const { id, dimensions } of measured) {
        if (!ids.has(id)) continue
        if (next[id]?.width !== dimensions.width || next[id]?.height !== dimensions.height) {
          next[id] = dimensions; changed = true
        }
      }
      return changed ? next : previous
    })
  }, [entries])
  const edges = useMemo(() => wires.map(wire => ({
    id: edgeId(wire), source: wire.source, sourceHandle: 'out', target: wire.target, targetHandle: wire.port,
    style: wire.source === 'empty_wall' ? { ...edgeStyle, opacity: 0.38 } : edgeStyle,
    selected: edgeId(wire) === selectedWire
  })), [wires, selectedWire])

  const transact = callback => {
    try {
      callback()
      setMessage(null)
    } catch (error) {
      setMessage({ error: true, text: error.message || String(error) })
    }
  }
  const commit = next => {
    inspect(mode, next)
    if (JSON.stringify(next) !== JSON.stringify(document)) {
      historyContext.current.set(document, { library, materials })
      // Reopening the same example must create a distinct history entry.
      history.push(structuredClone(next))
    }
  }
  const travelHistory = direction => {
    const next = direction === 'undo' ? history.past.at(-1) : history.future[0]
    if (!next) return
    historyContext.current.set(document, { library, materials })
    const context = historyContext.current.get(next)
    if (context) { setLibrary(context.library); setMaterials(context.materials) }
    history[direction]()
  }
  const patchNode = patch => transact(() => {
    const { node } = visibleNode
    let updated = { ...document.graph.nodes.find(item => item.id === node.id), ...patch }
    commit(editNode(mode, document, updated))
  })
  const setWire = (target, port, source) => transact(() => {
    const rest = wires.filter(wire => wire.target !== target || wire.port !== port)
    commit(applyWires(mode, document, entries, source ? [...rest, { source, target, port }] : rest))
    setSelectedWire(null)
  })
  const removeSelection = () => {
    const wire = wires.find(wire => edgeId(wire) === selectedWire)
    if (wire) {
      setWire(wire.target, wire.port, '')
    } else if (current && !protectedNode(current.node)) {
      transact(() => {
        const rest = wires.filter(wire => wire.source !== selected && wire.target !== selected)
        commit(applyWires(mode, document, entries, rest, [selected]))
        setSelected(null)
      })
    }
  }
  const open = raw => {
    commit(readGraph(raw, mode))
    setSelected(null)
    setSelectedWire(null)
    setSelectedIds([])
    setNodeMeasurements({})
    setWorkspace(key => key + 1)
  }
  const add = () => transact(() => {
    if (!kind) return
    if (mode === 'building' && kind === 'COPYCAT') { chooseCopycat(); return }
    const { left, top, width, height } = canvas.current.getBoundingClientRect()
    const center = flow?.screenToFlowPosition({ x: left + width * 0.5, y: top + height * 0.4 }) ?? { x: 50, y: 50 }
    const id = `node_${crypto.randomUUID().slice(0, 13)}`
    const node = newNode(mode === 'building', kind, id, position(center.x), position(center.y), true)
    if (kind === 'INPUT') node.part.group = formGroups(document)[0].id
    commit(replaceNodes(document, [...document.graph.nodes, node]))
    setSelected(id)
    setSelectedIds([id])
    setSelectedWire(null)
  })
  const onDrag = useCallback((_, node) => setDragging({ id: node.id, position: node.position }), [])
  const onDragStop = (_, node) => {
    setDragging(null)
    transact(() => commit(replaceNodes(document, document.graph.nodes.map(item => item.id === node.id
      ? { ...item, x: position(node.position.x), y: position(node.position.y) } : item))))
  }
  const compatible = ({ source, target, targetHandle }) => {
    const port = entries.find(({ node }) => node.id === target)?.ports.find(port => port.id === targetHandle)
    const sourceKind = entries.find(({ node }) => node.id === source)?.sourceKind || entries.find(({ node }) => node.id === source)?.node.kind
    return source !== target && Boolean(port?.accepts.includes(sourceKind))
  }
  const importFile = async (event, isLibrary) => {
    const file = event.target.files?.[0]
    event.target.value = ''
    if (!file) return
    try {
      if (file.size > 16_000_000) throw new Error('Die JSON-Datei ist größer als 16 MB.')
      const raw = JSON.parse(await file.text())
      if (isLibrary) {
        const imported = validateLibrary(raw)
        setLibrary({ forms: { ...baseLibrary.forms, ...imported.forms }, modules: { ...baseLibrary.modules, ...imported.modules } })
      }
      else open(raw)
      setMessage({ text: `${file.name} geöffnet` })
    } catch (error) {
      setMessage({ text: error.message, error: true })
    }
  }

  const loadCopycat = (entry, nodeId = visibleNode.node.kind === 'COPYCAT' || visibleNode.node.kind === 'SAVED' ? visibleNode.node.id : undefined) => {
    if (mode === 'building') {
      const result = loadBuildingCopycat(document, library, entry, nodeId)
      commit(result.document); setLibrary(result.library); setSelected(result.nodeId)
    } else {
      const result = loadFormCopycatWithMaterials(document, entry, nodeId, materials)
      commit(result.document); setMaterials(result.materials); setSelected(result.nodeId)
    }
  }
  const savedForms = useMemo(() => savedData.graphs.filter(entry => entry.mode === 'form'), [savedData.graphs])
  const chooseCopycat = nodeId => {
    const node = entries.find(entry => entry.node.id === nodeId)?.node
    const initialId = node?.kind === 'INPUT' ? `base:${node.part.source}` : node?.kind === 'COPYCAT' ? `library:${node.form}` : null
    setCopycatPicker({ nodeId, initialId, initialCategory: 'all' })
  }
  const applyCopycat = option => {
    let result
    if (option.referenceOption) { result = applyReference({ document, library, nodeId: copycatPicker.nodeId, option });result.materials = materials }
    else {
      const old = document.graph.nodes.find(n => n.id === copycatPicker.nodeId)
      const target = old?.kind === 'REFERENCE' ? { ...document, graph:{...document.graph,nodes:document.graph.nodes.map(n => n.id === old.id ? copycatFromReference(old) : n)} } : document
      result = applyCopycatOption({ mode, document:target, library, materials, nodeId:copycatPicker.nodeId, option })
      const overrides = old?.referenceOverrides || instanceOverrides(old)
      if (Object.keys(overrides).length) result.document = editNode(mode,result.document,{...result.document.graph.nodes.find(n=>n.id===result.nodeId),...overrides})
    }
    commit(result.document)
    setLibrary(result.library); setMaterials(result.materials); setSelected(result.nodeId); setSelectedIds([result.nodeId])
    setSelectedWire(null); setMessage(null)
  }
  const count = entries.length
  const maxNodes = mode === 'form' ? model.formNodes : model.buildingNodes
  const portLimits = mode === 'form' ? model.form.find(type => type.kind === visibleNode.node.kind)
    : model.buildingInputs[visibleNode.node.kind] ?? null
  const isFormSourceLimit = ['INPUT', 'SAVED'].includes(kind) && entries.filter(({ node }) => ['INPUT', 'SAVED'].includes(node.kind)).length >= model.formSources
  const previewSelected = current?.node.id ?? ''
  const exportFile = () => transact(() => downloadJson(writeGraph(mode, document), mode))
  const copyJson = async () => {
    try {
      const json = JSON.stringify(writeGraph(mode, document))
      await navigator.clipboard.writeText(json)
      setMessage({ text: hasReferences(document) ? 'Graphstudio-JSON mit gespeicherten Referenzversionen kopiert.' : 'Minecraft-JSON kopiert. Im passenden Editor im Spiel importieren.' })
    } catch (error) {
      setMessage({ error: true, text: `JSON konnte nicht kopiert werden: ${error.message || String(error)}. Nutze alternativ JSON exportieren.` })
    }
  }
  const keydown = event => {
    if (event.target.closest('input, textarea, select, [contenteditable=true]')) return
    if ((event.ctrlKey || event.metaKey) && ['z', 'y'].includes(event.key.toLowerCase())) {
      event.preventDefault()
      if (event.shiftKey || event.key.toLowerCase() === 'y') travelHistory('redo')
      else travelHistory('undo')
    }
    if (['Delete', 'Backspace'].includes(event.key) && (selected || selectedWire)) {
      event.preventDefault()
      removeSelection()
    }
  }
  return <div className="studio" data-pane={pane} hidden={!active} onKeyDown={keydown}>
    <div className="studio-navigation">
      <button onClick={() => chooseCopycat(['INPUT','SAVED','COPYCAT','REFERENCE'].includes(current?.node.kind) ? current.node.id : undefined)}>{mode === 'building' ? 'Referenz auswählen' : 'Copycat auswählen'}</button>
      <div className="pane-tabs" role="group" aria-label="Arbeitsansicht">{[['graph','Graph'],['preview','Vorschau'],['inspector','Eigenschaften']].map(([id,label]) => <button key={id} aria-pressed={pane === id} onClick={() => setPane(id)}>{label}</button>)}</div>
      <details className="studio-tools"><summary>Datei & Bibliothek</summary><PagedPanel label="Werkzeuge" onClose={event => {event.currentTarget.closest("details").open=false}}>
    <div className="toolbar">
      <label className="example-select"><span>BEISPIELE</span><select aria-label="Beispiel öffnen" value="" onChange={e => transact(() => open(examples[mode][Number(e.target.value)][1]))}>
        <option value="" disabled>Beispiel öffnen</option>
        {examples[mode].map(([name], i) => <option key={name} value={i}>{name}</option>)}
      </select></label>
      <button type="button" onClick={() => transact(() => open(mode === 'building' ? starterBuilding() :
        { schema: 3, name: 'Neue Form', parts: [{ source: 'copycats:copycat_slice' }] }))}>Neu</button>
      <button type="button" onClick={() => graphInput.current.click()}>JSON öffnen</button>
      <input hidden ref={graphInput} type="file" accept=".json,application/json" onChange={event => importFile(event, false)} />
      <button type="button" onClick={() => chooseCopycat(['INPUT', 'SAVED', 'COPYCAT', 'REFERENCE'].includes(current?.node.kind) ? current.node.id : undefined)}>{mode === 'building' ? 'Referenz auswählen' : 'Copycat auswählen'}</button>
      {mode === 'building' && <>
        <button type="button" onClick={() => copycatInput.current.click()}>Copycat laden</button>
        <input hidden ref={copycatInput} type="file" accept=".json,application/json" onChange={async event => {
          const file = event.target.files?.[0]; event.target.value = ''; if (!file) return
          try {
            if (file.size > 4_000_000) throw new Error('Die Copycat-Datei ist größer als 4 MB.')
            const source = readGraph(JSON.parse(await file.text()), 'form')
            transact(() => loadCopycat({ id: crypto.randomUUID(), name: source.name, document: source }))
          } catch(error) { setMessage({ text: error.message, error: true }) }
        }} />
        <button type="button" onClick={() => libraryInput.current.click()}>Bibliothek öffnen</button>
        <input hidden ref={libraryInput} type="file" accept=".json,application/json" onChange={event => importFile(event, true)} />
      </>}
      <button type="button" className="primary" onClick={exportFile}>JSON exportieren</button>
      <button type="button" onClick={copyJson}>{hasReferences(document) ? 'Graphstudio-JSON kopieren' : 'Minecraft-JSON kopieren'}</button>
      <span className="toolbar-spacer" />
      <button type="button" aria-label="Rückgängig" title="Rückgängig" disabled={!history.past.length} onClick={() => travelHistory('undo')}>↶</button>
      <button type="button" aria-label="Wiederholen" title="Wiederholen" disabled={!history.future.length} onClick={() => travelHistory('redo')}>↷</button>
    </div>

    <div className="personal-library-bar">
      <label>Name für Bibliothek<input aria-label="Name für Bibliothek" placeholder={document.name || 'Mein Entwurf'} maxLength={64} value={saveName} onChange={event => setSaveName(event.target.value)} /></label>
      <button onClick={() => transact(() => {
        const name = saveName.trim() || document.name || 'Graph'
        const existing = savedData.graphs.find(entry => entry.mode === mode && entry.name === name)
        if (savedData.graphs.length >= 500) throw new Error('Höchstens 500 Graphen speichern. Zuerst nicht mehr benötigte Einträge entfernen.')
        const entry = { id: crypto.randomUUID(), graphId: existing?.graphId || existing?.id || crypto.randomUUID(), version: Math.max(0,...savedData.graphs.filter(e => e.mode === mode && e.name === name).map(e => e.version || 1)) + 1, name, mode, document, library, materials }
        updateWorkspace(data => ({ ...data, graphs: [...data.graphs.filter(item => item.id !== entry.id), entry] }))
        setMessage({ text: 'Graph in deiner Browserbibliothek gespeichert.' })
      })}>Graph speichern</button>
      <select aria-label="Gespeicherten Graph öffnen" value="" onChange={event => transact(() => {
        const entry = savedData.graphs.find(entry => entry.id === event.target.value)
        open(entry.document); setLibrary(entry.library || baseLibrary); setMaterials(entry.materials || {})
      })}><option value="" disabled>Gespeicherte Graphen</option>{savedData.graphs.filter(entry => entry.mode === mode).map(entry => <option key={entry.id} value={entry.id}>{entry.name} · v{entry.version || 1}</option>)}</select>
      <button title="Mit Umschalt mehrere Knoten auswählen" disabled={!selectedIds.length && !current} onClick={() => transact(() => {
        const entry = saveNodeGroup(mode, document, selectedIds.length ? selectedIds : [selected], saveName, materials, library)
        const existing = savedData.nodeGroups.find(item => item.mode === mode && item.name === entry.name)
        if (!existing && savedData.nodeGroups.length >= 500) throw new Error('Höchstens 500 Knotengruppen speichern.')
        if (existing) entry.id = existing.id
        entry.version = (existing?.version || 0) + 1
        updateWorkspace(data => ({ ...data, nodeGroups: [...data.nodeGroups.filter(item => item.id !== entry.id), entry] }))
      })}>Knotengruppe speichern</button>
      <select aria-label="Knotengruppe einfügen" value="" onChange={event => transact(() => {
        const entry = savedData.nodeGroups.find(entry => entry.id === event.target.value)
        const nextLibrary = mergeLibraries(library, entry.library)
        const inserted = insertNodeGroup(document, entry); commit(inserted.document); setLibrary(nextLibrary); setMaterials(current => ({ ...current, ...inserted.materials })); setWorkspace(key => key + 1)
      })}><option value="" disabled>Knotengruppe einfügen</option>{savedData.nodeGroups.filter(entry => entry.mode === mode).map(entry => <option key={entry.id} value={entry.id}>{entry.name} · v{entry.version || 1}</option>)}</select>
    </div>

    </PagedPanel></details></div>
    <div className="workbench" style={{ '--graph-share': `${graphShare}fr`, '--side-share': `${100 - graphShare}fr` }}>
      <section className="canvas-panel" aria-label="Graph bearbeiten">
        <div className="panel-heading graph-heading">
          <div><span>{document.name || 'Entwurf'}</span><small>{count} / {maxNodes} Knoten</small></div>
          <div className="button-row">
            <select aria-label="Knotentyp" value={kind} onChange={event => setKind(event.target.value)}>
              <option value="" disabled>Knotentyp</option>
              {kinds.map(kind => <option key={kind} value={kind}>{names[kind]}</option>)}
            </select>
            <button type="button" disabled={!kind || count >= maxNodes || isFormSourceLimit} onClick={add}>+ Knoten</button>
          </div>
        </div>
        <div className="canvas" ref={canvas}>
          {active && <ReactFlow key={workspace} nodes={nodes} onNodesChange={onNodesChange} edges={edges} nodeTypes={nodeTypes} onInit={setFlow}
            fitView fitViewOptions={{ padding: 0.15, maxZoom: 1.1 }} minZoom={0.13} maxZoom={2}
            onNodeDrag={onDrag} onNodeDragStop={onDragStop} deleteKeyCode={null} nodesConnectable
            onNodeClick={(event, node) => { setSelected(node.id); setSelectedWire(null); setSelectedIds(ids => event.shiftKey ? ids.includes(node.id) ? ids.filter(id => id !== node.id) : [...ids, node.id] : [node.id]) }}
            onEdgeClick={(_, edge) => { setSelectedWire(edge.id); setSelected(edge.target) }}
            onPaneClick={() => { setSelected(null); setSelectedWire(null); setSelectedIds([]) }}
            isValidConnection={compatible} onConnect={wire => setWire(wire.target, wire.targetHandle, wire.source)}
            selectionKeyCode="Shift" multiSelectionKeyCode="Shift" onSelectionChange={onSelectionChange} onlyRenderVisibleElements
            colorMode="dark" aria-label="React-Flow-Graph">
            <Background gap={22} size={1} color="#46606d" />
            <Controls showInteractive={false} />
            <MiniMap pannable zoomable nodeColor="#659a9c" maskColor="#18232c88" />
          </ReactFlow>}
        </div>
        <div className={`canvas-footer ${message?.error ? 'error' : ''}`} role="status">
          {message?.text ?? 'Knoten ziehen · Anschlüsse verbinden · Entf löscht die Auswahl'}
        </div>
      </section>
      <SplitHandle axis="x" value={graphShare} onChange={setGraphShare} label="Breite von Graph und Seitenleiste" />
      <div className={`side${previewCollapsed && inspectorCollapsed ? ' both-collapsed' : ''}`}
        style={{ gridTemplateRows: previewCollapsed && inspectorCollapsed ? 'auto auto' : previewCollapsed ? 'auto minmax(0, 1fr)' : inspectorCollapsed ? 'minmax(0, 1fr) auto' : `minmax(0, ${previewShare}fr) 10px minmax(0, ${100-previewShare}fr)` }}>
        {active && <Suspense fallback={<section className="preview-panel"><div className="preview-meta">3D-Vorschau wird geladen …</div></section>}>
          <Preview mode={mode} document={document} library={library} selected={previewSelected} materials={materials}
            previewMode={previewMode} onMode={setPreviewMode} collapsed={previewCollapsed} onToggle={() => setPreviewCollapsed(value => !value)} />
        </Suspense>}
        {!previewCollapsed && !inspectorCollapsed && <SplitHandle axis="y" value={previewShare} onChange={setPreviewShare} label="Höhenverhältnis von 3D-Vorschau und Inspektor" />}
        <section className={`inspector${inspectorCollapsed ? ' is-collapsed' : ''}`} aria-label="Knoteninspektor">
          <div className="panel-heading"><span>Inspektor</span><div className="button-row"><small>{current ? 'AUSWAHL' : 'ERGEBNIS'}</small>
            <button type="button" aria-expanded={!inspectorCollapsed} aria-controls={`${mode}-inspector`} aria-label={inspectorCollapsed ? 'Inspektor ausklappen' : 'Inspektor einklappen'} onClick={() => setInspectorCollapsed(value => !value)}>{inspectorCollapsed ? '▸' : '▾'}</button>
          </div></div>
          <PagedPanel id={`${mode}-inspector`} label="Eigenschaften" className="inspector-body" hidden={inspectorCollapsed} key={visibleNode.node.id}>
            <a className="reference-link" href={`${wiki}/Knoten-${mode === 'form' ? 'Formgraph' : 'Gebaeudegraph'}#${visibleNode.node.kind.toLowerCase()}`}>Knotenreferenz ↗</a>
            <h2>{visibleNode.node.name || names[visibleNode.node.kind]}</h2>
            <div className="node-id">{visibleNode.node.id} · Ausgang: {visibleNode.outputType || 'Ergebnis'}</div>
            {visibleNode.ports.length > 0 && <details open><summary>Verbindungen</summary>
              {visibleNode.ports.map(port => <Field key={port.id} label={portName(port, visibleNode.node)}
                value={port.source} options={[[ '', 'Offen' ], ...entries.filter(({ node }) =>
                  port.accepts.includes(entries.find(e => e.node.id === node.id)?.sourceKind || node.kind) && node.id !== visibleNode.node.id)
                  .map(({ node }) => [node.id, node.name || `${names[node.kind]} · ${node.id}`])]}
                onChange={source => setWire(visibleNode.node.id, port.id, source)} />)}
              {portLimits && portLimits.min !== portLimits.max && <div className="button-row">
                <button type="button" disabled={visibleNode.node.inputs.length >= portLimits.max}
                  onClick={() => patchNode({ inputs: [...visibleNode.node.inputs, ''] })}>+ Eingang</button>
                <button type="button" disabled={visibleNode.node.inputs.length <= portLimits.min}
                  onClick={() => patchNode({ inputs: visibleNode.node.inputs.slice(0, -1),
                    ...(mode === 'form' ? { inputGroups: visibleNode.node.inputGroups.slice(0, visibleNode.node.inputs.length - 1) } : {})
                  })}>Letzten Eingang entfernen</button>
              </div>}
            </details>}
            {visibleNode.node.kind === 'REFERENCE' ? <><Field label="Knotenname" value={visibleNode.node.name || ''} maxLength={64} onChange={name => patchNode({name})} /><button onClick={() => chooseCopycat(visibleNode.node.id)}>Referenz auswählen …</button><p>{visibleNode.node.reference.id} · Version {visibleNode.node.reference.version}</p>{Object.keys(visibleNode.node.referenceOverrides || {}).length > 0 && <button onClick={() => patchNode({referenceOverrides:{}})}>Instanzeinstellungen zurücksetzen</button>}<p>Ausgang: {visibleNode.outputType}. Die gespeicherte Definition bleibt unverändert.</p><details><summary>Definition dieser Version ansehen</summary><p>{visibleNode.node.reference.document.name || visibleNode.node.name}</p>{visibleNode.node.reference.document.graph.nodes.map(n => <p key={n.id}>{n.name || names[n.kind] || n.kind} · {n.id}</p>)}</details></> : <NodeFields {...{ mode, node: visibleNode.node, document, edit: patchNode, library }}
              onInspectNode={id => { setSelected(id); setSelectedIds([id]); setSelectedWire(null) }}
              onChooseCopycat={() => chooseCopycat(visibleNode.node.id)}
              onImportCopycat={() => copycatInput.current.click()} />}
            {(selectedWire || current && !protectedNode(current.node)) &&
              <button className="danger" type="button" onClick={removeSelection}>{selectedWire ? 'Verbindung trennen' : 'Knoten löschen'}</button>}
            <NativeDocumentFields mode={mode} document={resolved?.document || document} managedGroups={resolved?.managedGroups || []} selection={selectedIds.filter(id => !resolved?.owned.has(id))} edit={next => transact(() => commit(resolved ? foldReferenceEdit(document,resolved,next) : next))} />
            <DocumentFields {...{ mode, document, library, materials, setMaterials }} edit={next => transact(() => commit(next))} />
          </PagedPanel>
        </section>
      </div>
    </div>
    {copycatPicker && <Suspense fallback={<p className="copycat-loading" role="status">Copycat-Auswahl wird geladen …</p>}>
      <CopycatPicker {...copycatPicker} mode={mode} savedForms={savedForms} savedGraphs={savedData.graphs} savedGroups={savedData.nodeGroups} library={library}
        onChoose={applyCopycat} onClose={() => setCopycatPicker(null)} />
    </Suspense>}
  </div>
}

function App() {
  const { openPanel } = useWorkspace()
  const [mode, setMode] = useState(initialMode)
  useEffect(() => {
    const url = new URL(window.location.href)
    url.searchParams.delete('graph')
    url.searchParams.set('editor', mode)
    window.history.replaceState(null, '', url)
  }, [mode])
  return <>
    <header className="page-header">
      <div className="brand-mark" aria-hidden="true">⬡</div>
      <div className="brand"><span>COPYCAT ARCHITECT · {model.release}</span><h1>Graphstudio</h1></div>
      <nav aria-label="Graph auswählen">
        <button type="button" aria-pressed={mode === 'form'} onClick={() => setMode('form')}>Formgraph</button>
        <button type="button" aria-pressed={mode === 'building'} onClick={() => setMode('building')}>Gebäudegraph</button>
      </nav>
      <button type="button" onClick={() => openPanel('materials')}>Meine Bibliotheken</button>
      <a href={wiki}>Zum Wiki ↗</a>
    </header>
    <main>
      <Studio mode="form" active={mode === 'form'} />
      <Studio mode="building" active={mode === 'building'} />
    </main>
  </>
}

createRoot(document.getElementById('root')).render(<Workspace><App /></Workspace>)
