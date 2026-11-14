import { hasReferences, expandReferences } from './references.js'
import { createContext, useContext, useEffect, useMemo, useState } from 'react'
import { inspectorContext, wireModels, editNativeGroup, groupInterfaceContext, editGroupInterface } from './generated/engine.mjs'

import { contract, getPath, inspectorFields, setPath } from './inspector-model.js'
import { TransformValues } from './TransformValues.jsx'
import { ArrangementGrid } from './ArrangementGrid.jsx'
import { model } from './model.js'
import { MaterialField } from './Workspace.jsx'

const models = wireModels()
const contextCarpets = model.carpets
const Context = createContext({})
const choiceLabels = { SEPARATED: 'Separiert', GROUPED: 'Gruppiert', NORMAL: 'Lokale Normale', FOOTPRINT: 'Aus Grundriss', SEGMENTS: 'Aus Segmenten', FLAT: 'Flachdach', PENT: 'Pultdach', GABLE: 'Satteldach', HIP: 'Walmdach', MANSARD: 'Mansarddach', HEIGHT: 'Firsthöhe', ANGLE: 'Dachneigung', rooms: 'Aus Räumen', walls: 'Aus Wänden', horizontal: 'Horizontal', vertical: 'Vertikal', depth: 'Tiefe', transform: 'Frei anordnen', flat: 'Flachdach', gable: 'Satteldach', AUTO: 'Automatisch', EXPLICIT: 'Explizit', OPEN: 'Offen', BOUNDARY: 'Raumgrenze', OPENING: 'Öffnung', STRUCTURE: 'Tragwerk', DECORATION: 'Dekoration' }
const labels = {
  countValue: 'Anzahl', roofType: 'Dachart', ridge: 'Firstrichtung', overhang: 'Dachüberhang', coordinates: 'Koordinaten', layoutCells: 'Zellenbelegung', assembly: 'Expliziter Aufbau', depth: 'Tiefe',
  regionCopies: 'Copycats je Bereich', roofLevel: 'Dachebene', roofConfig: 'Dachaufbau', levelIds: 'Geschosskennungen',
  stairwell: 'Treppenhaus', elevatorShaft: 'Aufzugsschacht', serviceShaft: 'Installationsschacht',
  accessInstances: 'Vertikale Erschließung', roomLayout: 'Raumanordnung', roomConfig: 'Raumaufbau', facade: 'Fassade',
  features: 'Öffnungen und Erweiterungen', attachments: 'Wandanschlüsse', bayWindow: 'Erker', dormer: 'Gaube',
  bayWindows: 'Erkerinstanzen', dormers: 'Gaubeninstanzen', joinWallEnds: 'Wandenden verbinden',
  fenced: 'Einfrieden', fenceBlock: 'Zaunblock', fenceHeight: 'Zaunhöhe', pathWidth: 'Wegbreite',
  groundDepth: 'Bodentiefe', groundBlock: 'Bodenblock', clearance: 'Freiraum', elevation: 'Höhenversatz',
  landscapeOffset: 'Geländeversatz', buildingOffset: 'Gebäudeversatz', sitePaths: 'Wegeverläufe',
  top: 'Oben', rise: 'Anstieg', run: 'Lauflänge', phase: 'Phase', slice: 'Periodenschnitt', variant: 'Dachvariante', projection: 'Position am Architektentisch', table: 'Tischposition', turn: 'Drehung', exact: 'Exakte Geometrie',
  exactClip: 'Exakter Ausschnitt', roof: 'Dachgeometrie', x: 'X', y: 'Y', z: 'Z', width: 'Breite', height: 'Höhe',
  length: 'Länge', position: 'Position', sourceIndex: 'Eingang', quarterTurns: 'Vierteldrehungen',
  instances: 'Instanzen', boundary: 'Umriss', boundaryRules: 'Randregeln', filler: 'Füllsegment',
  mode: 'Modus', heightOverride: 'Eigene Raumhöhe', interiorSource: 'Innenraumquelle', defaultPartition: 'Trennwand',
  front: 'Front', wall: 'Wand', floor: 'Boden', ceiling: 'Decke', geometry: 'Geometriequelle', layout: 'Anordnung',
  rotationAxis: 'Drehachse', materialMode: 'Materialmodus', role: 'Materialrolle', surfaces: 'Materialgruppen', module: 'Segmentquelle', block: 'Oberflächenblock', thickness: 'Dicke', occupied: 'Raumhöhe belegen', finish: 'Bodenbelag', offsets: 'Platzierungsversatz', rectangles: 'Grundrissflächen', patternAxis: 'Anordnung', storeyMode: 'Zusammensetzung', mirrorPositionsOnly: 'Nur Blockpositionen spiegeln', mirror: 'Spiegelung', plane: 'Spiegelebene', rotations: 'Rotationen', mirrors: 'Spiegelungen', p: 'Anstieg', q: 'Lauflänge', axis: 'Ausrichtung', slope: 'Dachneigung', upperSlope: 'Obere Dachneigung', breakRatio: 'Mansardknick', height16: 'Firsthöhe (hB)', knee16: 'Kniestock (hB)', overhang: 'Dachverlängerung (Blöcke)', overhang16: 'Dachverlängerung (hB)', storey: 'Zielgeschoss', area: 'Fläche', enclosed: 'Hülle', headroom: 'Durchgangshöhe', landingDepth: 'Podesttiefe', clockwise: 'Im Uhrzeigersinn', tread: 'Stufenquelle', landing: 'Podestquelle', enclosure: 'Schachtwandquelle', fromStorey: 'Ab Geschoss', toStorey: 'Bis Geschoss', stops: 'Haltestellen', sourceIndex: 'Quelleneingang', openings: 'Öffnungen', extensions: 'Säulenerweiterungen', position: 'Position', size: 'Abmessungen', policy: 'Wandregel', contribution: 'Raumbeitrag',
  inputs: 'Eingänge', outputs: 'Ausgänge', targets: 'Gebundene Anschlüsse', member: 'Gruppenmitglied', port: 'Anschluss', positions: 'Definitionslayout',
  type: 'Typ', name: 'Name', source: 'Quelle', id: 'Kennung', form: 'Form', family: 'Dachfamilie',
}
const label = key => labels[key] || key.replace(/([a-z])([A-Z])/g, '$1 $2')
function initial(type) {
  if (type.endsWith('?')) return initial(type.slice(0, -1))
  if (/^(List|Set)</.test(type)) return []
  if (type.startsWith('Map<')) return {}
  if (type === 'String') return ''
  if (type === 'Boolean') return false
  if (models.enums[type]) return models.enums[type][0]
  const value = structuredClone(models[type]?.default ?? 0)
  if (value && typeof value === 'object' && (value.id === '' || type === 'RoofTemplateFamily')) value.id = crypto.randomUUID()
  return value
}
function args(type) {
  const body = type.slice(type.indexOf('<') + 1, -1)
  let depth = 0
  for (let i = 0; i < body.length; i++) {
    if (body[i] === '<') depth++
    if (body[i] === '>') depth--
    if (body[i] === ',' && !depth) return [body.slice(0, i), body.slice(i + 1)]
  }
  return [body]
}
function Value({ type, value, change, title, path = '', settings = {} }) {
  const context = useContext(Context)
  const choices = settings.choices || context.references?.[path]
  const optional = type.endsWith('?')
  const base = type.replace(/\?$/, '')
  const pick = options => <label>{title}<select aria-label={title} value={value ?? ''} onChange={event => change(['Int', 'Long'].includes(base) ? Number(event.target.value) : event.target.value)}>
    {options.map(option => { const key = typeof option === 'object' ? option.value : option; return <option key={String(key)} value={key}>{typeof option === 'object' ? option.label : choiceLabels[option] || option}</option> })}
  </select></label>
  if (context.group && base === 'String' && path.endsWith('.type')) return pick(context.graphTypes)
  if (context.group && base === 'Int' && path.endsWith('.member')) {
    const output = path.startsWith('outputs.')
    const type = output ? getPath(context.face, path.replace(/\.member$/, '.type')) : null
    return pick([...(output ? [{ value: -1, label: 'Nicht verbunden' }] : []), ...context.members.filter(member => !output || member.outputType === type).map(member => ({ value: member.index, label: member.label }))])
  }
  if (context.group && path.endsWith('.port')) {
    const member = getPath(context.face, path.replace(/\.port$/, '.member'))
    const type = getPath(context.face, path.split('.').slice(0, 2).join('.') + '.type')
    return pick((context.members[member]?.ports || []).filter(port => port.types.includes(type)).map(port => port.key))
  }
  if (base === 'Int' && path.endsWith('.sourceIndex') && context.node) return pick(context.node.kind === 'SEGMENT_LAYOUT' ? Array.from({ length: 16 }, (_, index) => ({ value: index, label: `Copycat ${index + 1}` })) : context.node.inputs.map((source, index) => ({ value: index, label: `${index + 1}: ${source || 'Nicht verbunden'}` })))
  if (base === 'Int' && path === 'ridge') return pick([{ value: 0, label: 'Nord–Süd (Z)' }, { value: 1, label: 'Ost–West (X)' }])
  if (base === 'Int' && (path.endsWith('quarterTurns') || path === 'part.turn' || path === 'part.spin')) return pick([0, 1, 2, 3].map(value => ({ value, label: `${value * 90}°` })))
  if (choices && !optional) return pick(context.references?.[path] ? [{ value: '', label: 'Nicht verbunden' }, ...choices] : choices)
  if (base === 'String' && context.mode === 'building') {
    if (path === 'form' || /\.form$/.test(path)) return pick([{ value: '', label: 'Keine Form' }, ...Object.entries(context.library?.forms || {}).map(([value, form]) => ({ value, label: `${form.name || value} · ${value}` }))])
    if (/\.(storey|fromStorey|toStorey)$/.test(path)) return pick([{ value: '', label: 'Automatisch' }, ...(context.storeys || [])])
    if (path === 'block' && ['GARDEN', 'PATH'].includes(context.node?.kind)) return pick(context.surfaceBlocks || [])
    if (path === 'groundBlock') return pick(context.groundBlocks || [])
    if (path === 'fenceBlock') return pick(context.fenceBlocks || [])
    if (path === 'role') return pick(Object.keys(context.palette || {}).map(value => ({ value, label: ({ wall: 'Wand', frame: 'Rahmen', glass: 'Glas', roof: 'Dach', gable: 'Giebel', trim: 'Zierleiste' })[value] || value })))
    if (path === 'finish') return pick([{ value: '', label: 'Kein Belag' }, ...context.carpets])
  }
  if (optional) return <div className="native-optional">
    <label className="check"><input type="checkbox" checked={value != null} onChange={event => change(event.target.checked ? initial(base) : null)} />{title}</label>
    {value != null && <Value {...{ value, change, title, path, settings }} type={base} />}
  </div>
  if (/^(List|Set)</.test(type)) return <fieldset><legend>{title}</legend>
    {(value || []).map((item, i) => <div className="native-item" key={i}>
      <Value type={args(type)[0]} value={item} path={`${path}.${i}`} title={`${title} ${i + 1}`} change={next => change(value.map((old, j) => j === i ? next : old))} />
      <button type="button" aria-label={`${title} ${i + 1} entfernen`} onClick={() => change(value.filter((_, j) => j !== i))}>Entfernen</button>
    </div>)}
    <button type="button" disabled={value?.length >= (settings.maxItems || 4096)} onClick={() => { const item = initial(args(type)[0]); if (context.group && item && typeof item === 'object' && 'type' in item) item.type = context.graphTypes[0]; change([...(value || []), item]) }}>+ Eintrag</button>
  </fieldset>
  if (type.startsWith('Map<')) return <fieldset><legend>{title}</legend>
    {Object.entries(value || {}).map(([key, item]) => <div className="native-item" key={key}>
      <Value type={args(type)[1]} value={item} path={`${path}.${key}`} title={key} change={next => change({ ...value, [key]: next })} />
      <button type="button" onClick={() => change(Object.fromEntries(Object.entries(value).filter(([id]) => id !== key)))}>Entfernen</button>
    </div>)}
    <MapAdd numeric={args(type)[0] === 'Int'} onAdd={key => { if (!['__proto__', 'constructor', 'prototype'].includes(key) && !(key in (value || {}))) change({ ...value, [key]: initial(args(type)[1]) }) }} />
  </fieldset>
  if (type === 'RoofSlope' && !path.endsWith('breakRatio') && context.slopes) return <label>{title}<select value={JSON.stringify(value)} onChange={event => change(JSON.parse(event.target.value))}>
    {context.slopes.map(slope => <option key={`${slope.p}:${slope.q}`} value={JSON.stringify(slope)}>{slope.p}:{slope.q} · {(Math.atan(slope.p / slope.q) * 180 / Math.PI).toFixed(2)}°</option>)}
  </select></label>
  if (type === 'RoofPartParameters') {
    const parameters = value || models.RoofPartParameters.default
    return <fieldset><legend>{title}</legend>
      <label>Dachneigung<select value={`${parameters.rise}:${parameters.run}`} onChange={event => { const [rise, run] = event.target.value.split(':').map(Number); change({ ...parameters, rise, run, phase: 0, slice: 0 }) }}>
        {(context.slopes || model.roofSlopes).map(slope => <option key={`${slope.p}:${slope.q}`} value={`${slope.p}:${slope.q}`}>{slope.p}:{slope.q}</option>)}
      </select></label>
      {['phase', 'slice', 'thickness', 'variant'].map(key => <Value key={key} type={models.RoofPartParameters.fields[key]} value={parameters[key]} title={label(key)} path={`${path}.${key}`} settings={key === 'variant' ? { choices: ['slope', 'eave', 'edge', 'ridge', 'hip', 'valley', 'corner', 'cap'] } : { min: key === 'slice' ? -1 : key === 'phase' ? 0 : 1, max: key === 'slice' ? parameters.rise : key === 'phase' ? parameters.run - 1 : 16 }} change={next => change({ ...parameters, [key]: next })} />)}
    </fieldset>
  }
  if (models[type]) {
    const object = value || models[type].default || {}
    let fields = Object.entries(models[type].fields)
    const horizontalPoint = type === 'P' && (['buildingOffset', 'landscapeOffset'].includes(path) || /^accessInstances\.\d+\.position$/.test(path) || /^offsets\.\d+$/.test(path) && ['STOREY', 'BUILDING'].includes(context.node?.kind) || /^coordinates\.\d+$/.test(path) && ['AREA', 'ROOM_GEOMETRY', 'GARDEN'].includes(context.node?.kind))
    if (horizontalPoint) fields = fields.filter(([key]) => key !== 'y')
    if (type === 'RoofLevelConfig') fields = fields.filter(([key]) => !(['upperSlope', 'breakRatio'].includes(key) && object.form !== 'MANSARD') && !(key === 'height16' && object.mode !== 'HEIGHT') && !(key === 'knee16' && object.mode !== 'ANGLE'))
    return <fieldset><legend>{title}</legend>{fields.filter(([key]) => !(context.group && key === 'id')).map(([key, field]) =>
      <Value key={key} type={field} path={path ? `${path}.${key}` : key} settings={contract.structures?.[type]?.[key]} value={object[key] ?? models[type].default?.[key]} title={label(key)} change={next => change({ ...object, [key]: next })} />)}</fieldset>
  }
  if (models.enums[type]) return pick(models.enums[type])
  if (type === 'Boolean') return <label className="check"><input type="checkbox" checked={value || false} onChange={event => change(event.target.checked)} />{title}</label>
  return <label>{title}<input type={type === 'String' ? 'text' : 'number'} value={value ?? ''} step="1" min={settings.min} max={settings.max} onChange={event => {
    const next = type === 'String' ? event.target.value : Number(event.target.value)
    if (type === 'String' || Number.isSafeInteger(next)) change(next)
  }} /></label>
}
function MapAdd({ numeric, onAdd }) {
  const [key, setKey] = useState('')
  return <div className="button-row"><input aria-label="Neue Kennung" placeholder="Kennung" value={key} type={numeric ? 'number' : 'text'} onChange={event => setKey(event.target.value)} />
    <button type="button" disabled={!key || (numeric && !/^-?\d+$/.test(key))} onClick={() => { onAdd(key); setKey('') }}>+ Eintrag</button></div>
}
function DraftFields({ value, fields, types, onApply, title }) {
  const [draft, setDraft] = useState(() => structuredClone(value))
  useEffect(() => setDraft(structuredClone(value)), [JSON.stringify(value)])
  if (!fields.length) return null
  return <details className="native-fields"><summary>{title}</summary>
    {fields.map(key => <Value key={key} type={types[key]} value={draft[key]} title={label(key)} change={next => setDraft({ ...draft, [key]: next })} />)}
    <button type="button" className="primary" disabled={JSON.stringify(draft) === JSON.stringify(value)} onClick={() => onApply(Object.fromEntries(fields.map(key => [key, draft[key]])))}>Übernehmen</button>
  </details>
}
// These controls are already rendered by the native-style source/material widgets.
const formWidgets = {
  INPUT: ['part.source', 'part.discCut', 'part.size', 'part.group', 'part.turn', 'part.offset', 'part.tilt', 'part.spin'],
  SAVED: ['savedCopycat'], UNION: ['materialMode', 'materialGroup', 'inputGroups'],
  SYMMETRIC_DIFFERENCE: ['materialMode', 'materialGroup', 'inputGroups'],
  ROTATE: ['rotationAxis', 'quarterTurns'], ROTATION: ['rotationAxis', 'quarterTurns'], MIRROR: ['mirror.plane'], OUTPUT: ['previewGroup'],
}
export function NativeFields({ mode, node, edit, document, library, onInspectNode }) {
  const [draft, setDraft] = useState(() => structuredClone(node))
  useEffect(() => setDraft(structuredClone(node)), [JSON.stringify(node)])
  const fields = inspectorFields(mode, node).filter(field => !(mode === 'form' && formWidgets[node.kind]?.includes(field.path)))
  const context = useMemo(() => {
    if (mode !== 'building') return { mode, library, node: draft, slopes: model.roofSlopes }
    try {
      const altered = { ...document, graph: { ...document.graph, nodes: document.graph.nodes.map(item => item.id === node.id ? draft : item) } }
      const resolved = hasReferences(altered) ? expandReferences(altered,library) : {document:altered,library}
      const native = inspectorContext(resolved.document, node.id)
      const ids = new Set(document.graph.nodes.map(n => n.id))
      for (const key of Object.keys(native.references || {})) native.references[key] = native.references[key].filter(n => ids.has(n.value))
      return { ...native, mode, library:resolved.library, node: draft, carpets: contextCarpets }
    } catch { return { mode, library, node: draft, references: {}, carpets: [] } }
  }, [mode, document, library, draft])
  if (mode === 'building' && ['ROTATION', 'MIRRORING'].includes(node.kind)) return <TransformValues {...{ node, edit }} />
  if (!fields.length) return null
  const groups = library?.forms?.[draft.form]?.groups || [{ id: 'body', name: 'Body', role: 'surface' }]
  const patch = (path, next) => setDraft(setPath(draft, path, next))
  return <Context.Provider value={context}><details open className="native-fields"><summary>{mode === 'building' ? 'Knoteneinstellungen' : 'Ausrichtung und Formeinstellungen'}</summary>
    {node.kind === 'ROTATION_SEGMENT' && <p className="hint">Die Copycat wird bei 0°, 90°, 180° und 270° um Y vereinigt. Die Länge wiederholt dieses Profil entlang Y zur Säule.</p>}
    {(draft.kind === 'ROOF' && !draft.assembly && draft.roofType === 'gable' || draft.kind === 'ROOF_LEVEL' && ['GABLE', 'CROSS', 'PENT'].includes(draft.roofLevel?.form)) && <p className="hint">Die Dachverlängerung wächst entlang des Firsts über die Giebelseiten hinaus. Dachbreite und Mauern bleiben am Gebäudegrundriss.</p>}
    {fields.map(field => <div key={field.path} data-inspector-field={field.path}>
      {context.derived?.[field.path] ? <div><label>{label(field.path)}<output>{Array.isArray(getPath(context.resolved, field.path)) ? `${getPath(context.resolved, field.path).length} Einträge` : String(getPath(context.resolved, field.path))}</output></label><button type="button" onClick={() => onInspectNode?.(context.derived[field.path])}>Quelle bearbeiten</button></div>
        : field.path === 'module'  && node.kind === 'SEGMENT' ? <label>Segmentquelle<select value={draft.block === 'minecraft:air' ? '_air' : draft.module || '_solid'} onChange={event => {
        const value = event.target.value
        setDraft({ ...draft, module: value.startsWith('_') ? '' : value, block: value === '_air' ? 'minecraft:air' : '' })
      }}><option value="_solid">Massive Wand</option><option value="_air">Leere Wand</option>{Object.entries(library?.modules || {}).map(([id, item]) => <option key={id} value={id}>{item.name || id}</option>)}</select></label>
        : field.path === 'block' && node.kind === 'SEGMENT' ? null
        : field.path === 'surfaces' ? <fieldset><legend>Materialgruppen</legend>{groups.map(group => <MaterialField key={group.id} blockOnly label={group.name} value={draft.surfaces?.[group.id] || context.palette?.[group.role === 'surface' ? draft.role : group.role] || ''} onChange={value => patch('surfaces', Object.fromEntries(groups.map(item => [item.id, item.id === group.id ? value : draft.surfaces?.[item.id] || context.palette?.[item.role === 'surface' ? draft.role : item.role] || 'minecraft:stone'])))} />)}<button type="button" onClick={() => patch('surfaces', null)}>Gebäudepalette verwenden</button></fieldset>
        : <Value type={field.type} value={getPath(draft, field.path)} path={field.path} settings={field} title={label(field.path.split('.').at(-1))} change={next => patch(field.path, next)} />}
    </div>)}
    {(node.kind === 'PATTERN' || node.kind === 'SEGMENT_UNION' || node.kind === 'STOREY' && node.storeyMode === 'rooms') && <ArrangementGrid {...{ node, document: hasReferences(document) ? expandReferences(document,library).document : document, library:hasReferences(document) ? expandReferences(document,library).library : library, edit }} />}
    <button type="button" className="primary" disabled={JSON.stringify(draft) === JSON.stringify(node)} onClick={() => edit(Object.fromEntries(fields.map(field => field.path.split('.')[0]).map(key => [key, draft[key]])))}>Übernehmen</button>
  </details></Context.Provider>
}
function GroupInterfaceFields({ mode, document, group, onApply }) {
  const [face, setFace] = useState(() => structuredClone(group.graphInterface))
  useEffect(() => setFace(structuredClone(group.graphInterface)), [JSON.stringify(group.graphInterface)])
  const context = { ...groupInterfaceContext(mode === 'building', document, group.id), group: true, face }
  return <Context.Provider value={context}><div className="native-fields">
    <Value type="GraphGroupInterface" value={face} path="" title="Benannte Schnittstelle" change={setFace} />
    <button type="button" className="primary" onClick={() => onApply(face)}>Schnittstelle übernehmen</button>
  </div></Context.Provider>
}
export function NativeDocumentFields({ mode, document, edit, selection = [], managedGroups = [] }) {
  const [groupName, setGroupName] = useState('Gruppe')
  const [error, setError] = useState('')
  const [editing, setEditing] = useState(null)
  const act = (action, id = '') => {
    try {
      const graph = editNativeGroup(mode === 'building', document, action, id, groupName, selection)
      edit({ ...document, graph }); setError('')
      if (action === 'initialize') setEditing(id)
    } catch (error) { setError(error.message) }
  }
  return <>
    {mode === 'building' && <DraftFields value={document.graph} fields={['projection']} types={models.BuildingGraph.fields} title="Architektentisch" onApply={patch => edit({ ...document, graph: { ...document.graph, ...patch } })} />}
    <details><summary>Knotengruppen im Graphen</summary>
      <label>Gruppenname<input value={groupName} onChange={event => setGroupName(event.target.value)} maxLength={64} /></label>
      <button type="button" disabled={!selection.length} onClick={() => act('create')}>Auswahl gruppieren</button>
      {(document.graph.nodeGroups || []).filter(group => !managedGroups.includes(group.id)).map(group => <fieldset key={group.id}><legend>{group.name}</legend>
        <small>{group.nodes.length} Knoten · {group.graphInterface?.inputs.length || 0} benannte Eingänge</small>
        <div className="button-row"><button type="button" onClick={() => act('instantiate', group.id)}>Instanz erstellen</button>
          <button type="button" onClick={() => act('rename', group.id)}>Umbenennen</button>
          <button type="button" onClick={() => act('initialize', group.id)}>Schnittstelle bearbeiten</button>
          <button type="button" onClick={() => act('ungroup', group.id)}>Auflösen</button></div>
        {editing === group.id && group.graphInterface && <GroupInterfaceFields {...{ mode, document, group }} onApply={face => {
          try { edit({ ...document, graph: editGroupInterface(mode === 'building', document, group.id, face) }); setError('') }
          catch (error) { setError(error.message) }
        }} />}
      </fieldset>)}
      {error && <p className="error" role="status">{error}</p>}
    </details>
  </>
}
