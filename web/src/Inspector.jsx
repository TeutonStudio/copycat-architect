import { useState } from 'react'
import { TransformValues } from './TransformValues.jsx'
import { ArrangementGrid } from './ArrangementGrid.jsx'
import { NativeFields } from './NativeFields.jsx'
import { sourceMounting, mountSource } from './generated/engine.mjs'
import { formGroups, model } from './model.js'
import { MaterialField } from './Workspace.jsx'
import { copycatShapeNames } from './copycat-options.js'

export const names = {
  INT: 'Anzahl', ROTATION_SEGMENT: 'Rotationssegment', AREA: 'Fläche', SEGMENT_LAYOUT: 'Segmentanordnung', ROOM_GEOMETRY: 'Raumgrundriss', ROOF_LEVEL: 'Dachebene', ROOF_LAYOUT: 'Dachanordnung', STAIRWELL: 'Treppenhaus', ELEVATOR_SHAFT: 'Aufzugsschacht', SERVICE_SHAFT: 'Installationsschacht', GARDEN: 'Garten', PATH: 'Weg', ROOM_LAYOUT: 'Raumanordnung', BAY_WINDOW: 'Erker', DORMER: 'Gaube',
  INPUT: 'Grundform', UNION: 'Vereinigung', INTERSECTION: 'Schnitt', DIFFERENCE: 'Differenz',
  SYMMETRIC_DIFFERENCE: 'Symmetrische Differenz', ROTATE: 'Copycat rotieren', ROTATION: 'Rotation', OUTPUT: 'Ergebnis',
  SAVED: 'Gespeicherter Copycat', MIRROR: 'Copycat spiegeln',
  MIRRORING: 'Spiegelungen', COPYCAT_MIRROR: 'Copycat spiegeln', COPYCAT_ROTATE: 'Copycat rotieren',
  COPYCAT_UNION: 'Copycat-Vereinigung', COPYCAT_INTERSECTION: 'Copycat-Schnitt', COPYCAT_DIFFERENCE: 'Copycat-Differenz', COPYCAT_SYMMETRIC_DIFFERENCE: 'Symmetrische Copycat-Differenz',
  SEGMENT_MIRROR: 'Segment spiegeln', SEGMENT_ROTATE: 'Segment rotieren',
  SEGMENT_UNION: 'Segment-Vereinigung', SEGMENT_INTERSECTION: 'Segment-Schnitt', SEGMENT_DIFFERENCE: 'Segment-Differenz', SEGMENT_SYMMETRIC_DIFFERENCE: 'Symmetrische Segment-Differenz',
  REFERENCE: 'Referenz', COPYCAT: 'Referenz', SEGMENT: 'Wandsegment', PATTERN: 'Wandmuster', FLOOR: 'Boden', CEILING: 'Decke',
  ROOM: 'Raum', STOREY: 'Geschoss', ROOF: 'Dach', BUILDING: 'Gebäude'
}

export function portName(port, node) {
  if (port.name) return port.name
  const native = { length: 'Länge (Y)', roofPeak: 'Dachspitze (optional)', roofEave: 'Dachendkante / Traufe (optional)', layout: 'Anordnung', geometry: 'Grundriss', area: 'Fläche', tread: 'Stufen', landing: 'Podest', enclosure: 'Schachtwände' }
  if (native[port.id]) return native[port.id]
  if (port.id.startsWith('region:')) return `Bereich ${port.id.slice(7)}`
  if (port.id.startsWith('semantic:')) {
    const words = { bay: 'Erker', dormer: 'Gaube', wall: 'Wand', front: 'Front', floor: 'Boden', ceiling: 'Decke', roof: 'Dach', source: 'Quelle', opening: 'Öffnung', column: 'Säule', mask: 'Maske', inset: 'Einsatz' }
    return port.id.slice(9).split(':').map(word => words[word] || word).join(' · ')
  }
  if (port.id.startsWith('edge:')) return `Wandkante ${port.id.slice(5)}`
  if (port.id === 'rotation') return 'Rotationen'
  if (port.id === 'mirroring') return 'Spiegelungen'
  const [kind, key] = port.id.split(':')
  if (node.kind === 'SEGMENT_DIFFERENCE' && kind === 'in') return key === '0' ? 'A · Grundsegment' : 'B · Abzug'
  if (node.kind === 'COPYCAT_DIFFERENCE' && kind === 'in') return key === '0' ? 'A · Grundform' : 'B · Abzug'
  if (kind === 'in') {
    if (node.kind === 'ROOF' && node.assembly && Number(key) < 6) return ['Dachfläche', 'Dachendkante / Traufe', 'Ortgang', 'First', 'Grat', 'Kehle'][Number(key)]
    const family = node.kind === 'BUILDING' ? 'Außenanlage' : node.kind === 'ROOF_LAYOUT' ? 'Dachebene' : node.kind === 'ROOM_LAYOUT' || node.kind === 'PATTERN' || node.kind.startsWith('SEGMENT_') ? 'Segment' : node.kind === 'STOREY' ? 'Raum' : 'Copycat'
    return `${family} ${Number(key) + 1}`
  }
  if (kind === 'wall' && node.kind === 'ROOF') return `${node.roofType === 'gable' ? 'Giebel' : 'Dachrand'} · ${['Nord', 'Ost', 'Süd', 'West'][Number(key)]}`
  if (kind === 'wall') return `Wand ${port.id.slice(5)}${port.length ? ` · ${port.length} B` : ''}`
  if (kind === 'level') return Number(key) === 0 ? 'Erdgeschoss' : `Geschoss ${Number(key) > 0 ? '+' : ''}${key}`
  if (kind === 'access') return `Zugang ${Number(key) + 1}`
  if (kind === 'floor') return 'Boden'
  if (kind === 'ceiling') return 'Decke'
  if (kind === 'corner') return 'Ecke'
  if (kind === 'roof') return 'Dach'
  if (node.kind === 'ROTATE' && kind === '1') return 'Rotation'
  if (node.kind === 'DIFFERENCE') return kind === '0' ? 'A' : 'B'
  return `Eingang ${Number(kind) + 1}`
}

export function Field({ label, value, onChange, options, type = 'text', min, max, list, maxLength, step = 1 }) {
  const [draft, setDraft] = useState(null)
  if (options) return <label>{label}<select aria-label={label} value={value} onChange={e => onChange(typeof value === 'number' ? Number(e.target.value) : e.target.value)}>
    {options.map(option => {
      const [id, text] = Array.isArray(option) ? option : [option, option]
      return <option key={id} value={id} disabled={Array.isArray(option) && !!option[2]}>{text}</option>
    })}
  </select></label>
  if (type === 'checkbox') return <label className="check"><input aria-label={label} type="checkbox" checked={value ?? false} onChange={e => onChange(e.target.checked)} />{label}</label>
  return <label>{label}<input aria-label={label} value={draft ?? value} type={type} min={min} max={max} step={step} list={list} maxLength={maxLength}
    onChange={e => setDraft(e.target.value)}
    onBlur={() => {
      if (draft !== null) {
        setDraft(null)
        const next = type === 'number' ? Number(draft) : draft
        if (type === 'number' && (!draft.trim() || !Number.isInteger(next) || next < min || next > max)) return
        if (next !== value) onChange(next)
      }
    }}
    onKeyDown={e => {
      if (e.key === 'Enter') e.currentTarget.blur()
      if (e.key === 'Escape') setDraft(null)
    }} /></label>
}

const directions = [[2, 'Nord (−Z)'], [3, 'Ost (+X)'], [0, 'Süd (+Z)'], [1, 'West (−X)']]
const angles = [[0, '0°'], [1, '90°'], [2, '180°'], [3, '270°']]
const mountings = [['up', 'Oben (+Y)'], ['down', 'Unten (−Y)'], ['east', 'Seitlich +X'], ['west', 'Seitlich −X'], ['south', 'Seitlich +Z'], ['north', 'Seitlich −Z']]
const axes = [['X', 'X · Ost/West'], ['Y', 'Y · Höhe'], ['Z', 'Z · Süd/Nord'], ['NORMAL', 'Lokale Normale']]
const groupOptions = (groups, empty) => [['', empty], ...groups.map(group => [group.id, group.name])]
const materialModes = [['SEPARATED', 'Separiert'], ['GROUPED', 'Gruppiert']]
const viewFilters = [['all', 'Alles'], ['walls', 'Wände'], ['floor', 'Boden'], ['ceiling', 'Decke'], ['roof', 'Dach']]
const typeNames = [['flat', 'Flachdach'], ['gable', 'Satteldach']]

function NumberGrid({ entries, keys, ranges, update, prefix, scale = 1, unit = '' }) {
  return entries.map((entry, index) => <div key={index} className="number-grid">
    {keys.map(key => <Field key={key} label={`${prefix} ${index + 1} · ${key.toUpperCase()}${unit ? ` (${unit})` : ''}`} value={entry[key] * scale} type="number"
      step={scale} min={ranges[key][0] * scale} max={ranges[key][1] * scale} onChange={value => { if (Number.isInteger(value / scale)) update(index, { ...entry, [key]: value / scale }) }} />)}
  </div>)
}

export function NodeFields({ mode, node, document, edit, library, onChooseCopycat, onImportCopycat, onInspectNode }) {
  const field = (key, label, options, type, min, max) =>
    <Field key={key} label={label} value={node[key]} onChange={value => edit({ [key]: value })} {...{ options, type, min, max }} />
  const number = (key, label, min, max) => field(key, label, undefined, 'number', min, max)
  const groups = formGroups(document)
  const source = model.shapes.find(shape => shape.id === node.part?.source)
  const sourceName = node.kind === 'INPUT' ? copycatShapeNames[source?.name] || source?.name
    : node.kind === 'SAVED' ? node.savedCopycat?.name : library.forms?.[node.form]?.name || node.form
  const copycatPicker = <button type="button" className="copycat-source-button" aria-label={mode === 'building' ? 'Referenz auswählen' : 'Copycat auswählen'} onClick={onChooseCopycat}>
    <span>{mode === 'building' ? 'Referenz auswählen …' : 'Copycat auswählen …'}</span><small>{sourceName || 'Grundform oder gespeicherte Form'}</small>
  </button>
  if (mode === 'form') {
    const part = node.part
    const partField = (key, label, options, type, min, max) =>
      <Field key={key} label={label} value={part[key]} onChange={value => edit({ part: { ...part, [key]: value } })}
        {...{ options, type, min, max }} />
    const adapter = model.shapes.find(shape => shape.id === part.source)
    const groupFields = ['UNION', 'SYMMETRIC_DIFFERENCE'].includes(node.kind)
    return <>
      <NativeFields {...{ mode, node, edit, document, library, onInspectNode }} />
      {node.kind === 'SAVED' && copycatPicker}
      {node.kind === 'MIRROR' && <Field label="Spiegelebene" value={node.mirror?.plane || 'X'} options={model.mirrorPlanes} onChange={plane => edit({ mirror: { plane } })} />}
      {node.kind === 'INPUT' && adapter && <>
        {copycatPicker}
        {partField('size', 'Größe', undefined, 'number', 1, adapter.max)}
        {partField('turn', 'Drehung um Y', directions)}
        {partField('spin', 'Drehung um die lokale Normale', angles)}
        <Field label="Neigung" value={sourceMounting(part)} options={mountings.map(([id, label]) => [id, label, !adapter.tiltable && !['up', 'down'].includes(id)])} onChange={normal => edit({ part: { ...part, ...mountSource(part, normal) } })} />
        {part.source === 'copycats:copycat_flat_pane' && partField('discCut', 'Scheibenanteil', undefined, 'number', 0, 2)}
        <p className="hint">{!adapter.tiltable && 'Diese native Quelle erlaubt nur Oben/Unten. '}Neigung richtet die lokale Normale aus. Drehung um Y dreht die Form einschließlich ihrer Neigung.</p>
        {partField('group', 'Materialgruppe', groups.map(group => [group.id, group.name]))}
        <NumberGrid entries={[part.offset]} keys={['x', 'y', 'z']} ranges={{ x: [-15, 15], y: [-15, 15], z: [-15, 15] }}
          prefix="Versatz" unit="hB" update={(_, offset) => edit({ part: { ...part, offset } })} />
      </>}
      {['ROTATE', 'ROTATION'].includes(node.kind) && <>
        {field('rotationAxis', 'Achse', axes)}
        {field('quarterTurns', 'Drehwinkel', angles)}
      </>}
      {groupFields && field('materialMode', 'Materialmodus', materialModes)}
      {node.materialMode === 'GROUPED' && field('materialGroup', 'Ergebnisgruppe', groupOptions(groups, 'Erste Gruppe'))}
      {groupFields && node.materialMode === 'SEPARATED' && node.inputs.map((_, index) =>
        <Field key={index} label={`Gruppe Eingang ${index + 1}`} options={groupOptions(groups, 'Vom Eingang übernehmen')}
          value={node.inputGroups[index] ?? ''} onChange={group => edit({
            inputGroups: node.inputs.map((_, i) => i === index ? group : node.inputGroups[i] ?? '')
          })} />)}
      {node.kind === 'OUTPUT' && field('previewGroup', 'Vorschaugruppe', groupOptions(groups, 'Alle'))}
      {node.kind === 'DIFFERENCE' && <button type="button" onClick={() => edit({ inputs: [...node.inputs].reverse() })}>A / B tauschen</button>}
    </>
  }

  if (node.id === 'empty_wall') return <p className="hint">Standardquelle für eine freie Wandöffnung.</p>
  return <>
    {field('name', 'Knotenname')}
    {node.kind === 'COPYCAT' && <>{copycatPicker}<button type="button" onClick={onImportCopycat}>Formdatei laden</button></>}
    <NativeFields {...{ mode, node, edit, document, library, onInspectNode }} />
    {['ROOM', 'STOREY', 'BUILDING'].includes(node.kind) && field('preview', 'Vorschaufilter', viewFilters)}
    {['COPYCAT_DIFFERENCE', 'SEGMENT_DIFFERENCE'].includes(node.kind) && <button type="button" onClick={() => edit({ inputs: [...node.inputs].reverse() })}>A / B tauschen</button>}
  </>
}

export function DocumentFields({ mode, document, edit, library, materials, setMaterials }) {
  const groups = formGroups(document)
  const usedGroups = new Set(document.graph.nodes.flatMap(node => [
    node.kind === 'INPUT' ? node.part?.group : null, ...(node.savedCopycat?.parts ?? []).map(part => part.group || 'body'),
    node.materialGroup, node.previewGroup, ...(node.inputGroups ?? [])
  ]))
  function updateGroup(id, patch) {
    edit({ ...document, groups: groups.map(group => group.id === id ? { ...group, ...patch } : group) })
  }
  function addGroup() {
    const id = Array.from({ length: 9 }, (_, i) => `group${i + 1}`).find(id => groups.every(group => group.id !== id))
    const priority = Array.from({ length: 65 }, (_, i) => i - 32).find(value => groups.every(group => group.priority !== value) && value > 0)
    edit({ ...document, groups: [...groups, { id, name: id, role: 'surface', priority }] })
  }
  const palette = document.palette ?? {}
  const [role, setRole] = useState('')
  return <details className="document-fields"><summary>{mode === 'form' ? 'Dokument und Materialgruppen' : 'Dokument und Materialpalette'}</summary>
    <Field label="Dokumentname" value={document.name ?? 'Entwurf'} maxLength={64}
      onChange={name => edit({ ...document, name })} />
    {mode === 'form' ? <>
      {groups.map(group => <fieldset key={group.id}><legend>{group.id}</legend>
        <Field label="Name" value={group.name} maxLength={32} onChange={name => updateGroup(group.id, { name })} />
        <Field label="Materialrolle" value={group.role} onChange={role => updateGroup(group.id, { role })} />
        <MaterialField label={`Material ${group.name}`} value={materials[group.id] || ''} onChange={value => setMaterials(previous => ({ ...previous, [group.id]: value }))} />
        <Field label="Vordergrund" value={group.priority} type="number" min={-32} max={32}
          onChange={priority => updateGroup(group.id, { priority })} />
        <button type="button" disabled={groups.length <= 1 || usedGroups.has(group.id)}
          onClick={() => edit({ ...document, groups: groups.filter(item => item.id !== group.id) })}>Gruppe entfernen</button>
      </fieldset>)}
      <button type="button" disabled={groups.length >= 8} onClick={addGroup}>+ Materialgruppe</button>
    </> : <>
      {Object.entries(palette).map(([id, material]) => <div key={id} className="palette-row">
        <MaterialField label={id} value={material} blockOnly onChange={value => edit({ ...document, palette: { ...palette, [id]: value } })} />
        <button type="button" aria-label={`Rolle ${id} entfernen`} onClick={() => edit({
          ...document, palette: Object.fromEntries(Object.entries(palette).filter(([key]) => key !== id))
        })}>×</button>
      </div>)}
      <label>Neue Materialrolle<input value={role} onChange={event => setRole(event.target.value)} /></label>
      <button type="button" disabled={!/^[a-zA-Z0-9_-]{1,32}$/.test(role) || Object.hasOwn(palette, role) || Object.keys(palette).length >= 16}
        onClick={() => { edit({ ...document, palette: { ...palette, [role]: 'minecraft:stone' } }); setRole('') }}>+ Rolle</button>
      <p className="hint">{Object.keys(library.forms ?? {}).length} Formen · {Object.keys(library.modules ?? {}).length} Module geladen</p>
    </>}
  </details>
}
