import { createContext, useContext, useEffect, useMemo, useRef, useState } from 'react'
import { createMaterials, FAITHFUL, importRepository, materialValue, parseMaterial, stateOptions, defaultProperties } from './materials.js'
import { emptyWorkspace, resourceCache, saveWorkspace, stored, validateWorkspace } from './storage.js'
import { PagedPanel } from './PagedPanel.jsx'
import { readGraph } from './model.js'

const Context = createContext(null)
export const useWorkspace = () => useContext(Context)
export function downloadBackup(data, name = 'graphstudio-sicherung.json') {
  const url = URL.createObjectURL(new Blob([JSON.stringify(data)], { type: 'application/json' }))
  const a = document.createElement('a'); a.href = url; a.download = name; a.click()
  setTimeout(() => URL.revokeObjectURL(url), 1000)
}
export function Workspace({ children }) {
  const [data, setData] = useState(null), [error, setError] = useState(''), [saved, setSaved] = useState(false), [canSave, setCanSave] = useState(true)
  const [panel, setPanel] = useState(false), [tab, setTab] = useState('materials')
  const writeQueue = useRef(Promise.resolve())
  const revision = useRef(0)
  const storedRevision = useRef(0)
  const lastSavedData = useRef(null)
  useEffect(() => { stored('workspace', 'current').then(value => {
    const workspace = value ? validateWorkspace(value) : emptyWorkspace()
    storedRevision.current = workspace._revision || 0
    for (const entry of Object.values(workspace.drafts)) readGraph(entry.document, entry.mode)
    lastSavedData.current = workspace
    setData(workspace)
  })
    .catch(error => { setError(error.message); setCanSave(false); setData(emptyWorkspace()) }) }, [])
  useEffect(() => {
    if (!data || !canSave) return
    if (data === lastSavedData.current) { setSaved(true); return }
    const current = ++revision.current
    setSaved(false)
    // Transactions are serialized so a slower old save can never replace a newer one.
    writeQueue.current = writeQueue.current.catch(() => {}).then(async () => { storedRevision.current = await saveWorkspace(data, storedRevision.current); lastSavedData.current = data })
    writeQueue.current.then(() => { if (current === revision.current) { setSaved(true); setError('') } }).catch(error => setError(error.message))
  }, [data, canSave])
  const catalog = useMemo(() => createMaterials(data?.mods, resourceCache), [data?.mods])
  const update = callback => setData(previous => callback(previous))
  if (!data) return <p className="workspace-status">Gespeicherte Bibliotheken werden geladen …</p>
  return <Context.Provider value={{ data, update, catalog, openPanel: target => { setTab(target); setPanel(true) } }}>
    <div className="app-shell" inert={panel ? true : undefined}>{children}
    <div className="workspace-status" role="status">{error || (saved ? 'Im Browser gespeichert' : 'Wird im Browser gespeichert …')}
      {error && <button onClick={() => downloadBackup(data)}>Sicherung exportieren</button>}</div>
    <div className="texture-credit">Minecraft-Texturen: <a href="https://faithfulpack.net" target="_blank" rel="noreferrer">Faithful 64× Java · Faithful Resource Pack</a> · <a href="https://faithfulpack.net/license" target="_blank" rel="noreferrer">Lizenz</a></div></div>
    {panel && <LibraryPanel tab={tab} setTab={setTab} onClose={() => setPanel(false)} />}
  </Context.Provider>
}

export function MaterialField({ label, value, onChange, blockOnly = false }) {
  const { catalog } = useWorkspace()
  const [open, setOpen] = useState(false), [preview, setPreview] = useState(null)
  useEffect(() => { let live = true; setPreview(null); if (value) catalog.resolve(value).then(data => { if (live) setPreview(data) }).catch(() => {}); return () => { live = false } }, [catalog, value])
  return <div className="material-field"><label>{label}<div className="material-current">
    {preview?.faces.find(Boolean) && <img src={preview.faces.find(Boolean)} alt="" loading="lazy" />}
    <input value={value || ''} onChange={event => onChange(event.target.value)} />
    <button type="button" aria-label={`Material für ${label} wählen`} onClick={() => setOpen(!open)}>Wählen</button>
  </div></label>{open && <>{blockOnly && <p className="hint">Die Gebäudepalette übernimmt die Block-ID. Zustände dienen hier zum Nachschlagen; Copycat-Gruppen können sie für die Browservorschau speichern.</p>}<MaterialPicker value={value} onChoose={value => { onChange(blockOnly ? parseMaterial(value).block : value); setOpen(false) }} /></>}</div>
}
function MaterialPicker({ value, onChoose }) {
  const { catalog } = useWorkspace()
  const [query, setQuery] = useState(''), [limit, setLimit] = useState(60), [chosen, setChosen] = useState(null)
  const [properties, setProperties] = useState({}), [options, setOptions] = useState({}), [preview, setPreview] = useState(null), [error, setError] = useState('')
  const generation = useRef(0)
  const ids = catalog.ids.filter(id => id.includes(query.toLowerCase().trim()))
  const choose = async id => {
    const request = ++generation.current; setChosen(id); setError(''); setPreview(null); setProperties({}); setOptions({})
    try { const state = await catalog.state(id); if (generation.current !== request) return
      setOptions(stateOptions(state)); setProperties(defaultProperties(state))
    } catch (error) { if (generation.current === request) setError(error.message) }
  }
  useEffect(() => {
    if (!chosen) return
    let live = true; setError('')
    catalog.resolve(materialValue(chosen, properties)).then(data => { if (live) setPreview(data) }).catch(error => { if (live) setError(error.message) })
    return () => { live = false }
  }, [catalog, chosen, properties])
  return <div className="material-picker">
    <label>Blöcke suchen<input type="search" placeholder="minecraft:stone oder modid:block" value={query} onChange={event => { setQuery(event.target.value); setLimit(60) }} /></label>
    <small>{ids.length} Blöcke · {value || 'Noch kein Material gewählt'}</small>
    <div className="material-list">{ids.slice(0, limit).map(id => <button key={id} type="button" className={chosen === id ? 'chosen' : ''} onClick={() => choose(id)}>{id}</button>)}</div>
    {ids.length > limit && <button type="button" onClick={() => setLimit(limit + 100)}>Weitere Blöcke</button>}
    {chosen && <div className="material-detail"><strong>{chosen}</strong><small>{catalog.owner(chosen)}</small>
      {Object.entries(options).map(([key, values]) => <label key={key}>{key}<select value={properties[key] || ''} onChange={event => setProperties({ ...properties, [key]: event.target.value })}>{values.map(value => <option key={value}>{value}</option>)}</select></label>)}
      {preview?.faces.find(Boolean) && <img className="material-sample" src={preview.faces.find(Boolean)} alt={`Textur von ${chosen}`} />}
      {preview?.missing.length > 0 && <p className="error">Fehlende Texturen: {preview.missing.join(', ')}</p>}
      {preview?.warning && <p>{preview.warning}</p>}
      {error && <p className="error" role="status">{error}</p>}
      {onChoose && <button className="primary" type="button" disabled={!preview || Boolean(error)} onClick={() => onChoose(materialValue(chosen, properties))}>Dieses Material verwenden</button>}
    </div>}
  </div>
}
function LibraryPanel({ tab, setTab, onClose }) {
  const { data, update, catalog } = useWorkspace()
  const [url, setUrl] = useState(''), [ref, setRef] = useState(''), [root, setRoot] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState('')
  const input = useRef(null)
  const importMod = async (source = null) => {
    setBusy(true); setError('')
    try { const mod = await importRepository(source?.url || url, source?.ref || ref, source?.root || root)
      if (!data.mods.some(item => item.repo === mod.repo) && data.mods.length >= 100) throw new Error('Höchstens 100 Mod-Repositories verlinken.')
      update(data => ({ ...data, mods: [...data.mods.filter(item => item.repo !== mod.repo), mod] })); if (!source) setUrl('')
    } catch (error) { setError(error.message) } finally { setBusy(false) }
  }
  const restore = async event => {
    const file = event.target.files?.[0]; event.target.value = ''; if (!file) return
    try {
      if (file.size > 30_000_000) throw new Error('Die Sicherung ist größer als 30 MB.')
      const restored = validateWorkspace(JSON.parse(await file.text()))
      for (const entry of [...restored.graphs, ...Object.values(restored.drafts)]) readGraph(entry.document, entry.mode)
      // Merge named entries; current unsaved drafts stay intact.
      const recovered = Object.values(restored.drafts).map(entry => ({ ...entry, id: crypto.randomUUID(), name: `Wiederhergestellt · ${entry.document.name || entry.mode}` }))
      update(data => ({ ...data, graphs: [...new Map([...data.graphs, ...restored.graphs, ...recovered].map(item => [item.id, item])).values()],
        nodeGroups: [...new Map([...data.nodeGroups, ...restored.nodeGroups].map(item => [item.id, item])).values()],
        mods: [...new Map([...data.mods, ...restored.mods].map(item => [item.repo, item])).values()] }))
      setError('Sicherung zusammengeführt. Benannte Graphen können im Editor geöffnet werden.')
    } catch (error) { setError(error.message) }
  }
  const linkedIds = new Set(['minecraft', 'neoforge', 'forge', 'fabricloader', 'java', ...data.mods.flatMap(mod => [...mod.ids, ...Object.keys(mod.blocks).map(id => id.split(':')[0])])])
  return <section className="library-overlay" role="dialog" aria-modal="true" aria-label="Meine Bibliotheken" onKeyDown={event => {
    if (event.key === 'Escape') onClose()
    if (event.key === 'Tab') {
      const focusable = [...event.currentTarget.querySelectorAll('button:not(:disabled),input:not([hidden]),select,a[href]')]
      const first = focusable[0], last = focusable.at(-1)
      if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() }
      else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() }
    }
  }}>
    <div className="library-window"><div className="panel-heading"><h2>Meine Bibliotheken</h2><button onClick={onClose} autoFocus>Schließen</button></div>
      <nav className="library-tabs" aria-label="Bibliothek auswählen">{[['materials', 'Materialien'], ['mods', 'Verlinkte Mods'], ['saved', 'Gespeicherte Inhalte']].map(([id, name]) => <button key={id} aria-pressed={tab === id} onClick={() => setTab(id)}>{name}</button>)}</nav>
      <PagedPanel className="library-content" label="Bibliothek">
        {tab === 'materials' && <><p>{catalog.ids.length} Blöcke. Texturen für Minecraft 1.21.1: <a href={FAITHFUL} target="_blank" rel="noreferrer">Faithful 64× Java</a> von <a href="https://faithfulpack.net" target="_blank" rel="noreferrer">Faithful Resource Pack</a> · <a href="https://faithfulpack.net/license" target="_blank" rel="noreferrer">Lizenz</a>.</p>
          <p className="hint">Materialien im Inspektor einer Copycat-Gruppe oder Gebäuderolle zuweisen. Ob ein Block als Copycat-Material zulässig ist, prüft das Spiel.</p><MaterialPicker /></>}
        {tab === 'mods' && <><p>Öffentliches GitHub-Repository verlinken. Blockstates, Modell-Eltern, Texturen und Mod-Metadaten werden gelesen. Mod-Code wird nicht ausgeführt.</p>
          <form onSubmit={event => { event.preventDefault(); importMod() }}><label>Mod-Repository<input type="url" required placeholder="https://github.com/Autor/Mod" value={url} onChange={event => setUrl(event.target.value)} /></label>
            <label>Zweig, Tag oder Commit<input placeholder="Standardzweig" value={ref} onChange={event => setRef(event.target.value)} /></label>
            <label>Ressourcenordner (optional)<input placeholder="z. B. common/src/main/resources" value={root} onChange={event => setRoot(event.target.value)} /></label>
            <button className="primary" disabled={busy}>{busy ? 'Repository wird gelesen …' : 'Mod verlinken'}</button></form>
          {data.mods.map(mod => <article className="saved-entry" key={mod.repo}><strong><a href={mod.url} target="_blank" rel="noreferrer">{mod.repo}</a></strong><small>{mod.ref} · {mod.commit.slice(0, 8)} · {Object.keys(mod.blocks).length} Blöcke · Lizenz: {mod.license}</small>
            {mod.dependencies.map((dependency, index) => <p key={index} className={dependency.required && !linkedIds.has(dependency.id) ? 'error' : 'hint'}>{dependency.id} {dependency.version} · {dependency.required ? 'erforderlich' : 'optional'}{!linkedIds.has(dependency.id) && dependency.required ? ' · Repository noch verlinken' : ''}</p>)}
            <div className="button-row"><button disabled={busy} onClick={() => importMod(mod)}>Aktualisieren</button><button onClick={() => update(data => ({ ...data, mods: data.mods.filter(item => item.repo !== mod.repo) }))}>Verknüpfung entfernen</button></div>
          </article>)}<p className="hint">Eigene Java-Modell-Loader und erst beim Bauen erzeugte Dateien sind ohne veröffentlichte Ressourcen nicht darstellbar. Fehlende Abhängigkeiten als weiteres Repository verlinken.</p></>}
        {tab === 'saved' && <><p>{data.graphs.length} gespeicherte Graphen · {data.nodeGroups.length} Knotengruppen. Öffnen und Einfügen erfolgt über die Bibliothek-Leiste des jeweiligen Editors.</p>
          {[...data.graphs.map(item => ({ ...item, type: 'Graph' })), ...data.nodeGroups.map(item => ({ ...item, type: 'Knotengruppe' }))].map(item => <article className="saved-entry" key={item.id}><strong>{item.name}</strong><small>{item.type} · {item.mode === 'form' ? 'Copycat' : 'Gebäude'}</small><button onClick={() => update(data => ({ ...data, [item.type === 'Graph' ? 'graphs' : 'nodeGroups']: data[item.type === 'Graph' ? 'graphs' : 'nodeGroups'].filter(entry => entry.id !== item.id) }))}>Entfernen</button></article>)}
          <p>Die Daten bleiben in diesem Browser und auf dieser Website. Das Löschen der Websitedaten entfernt auch die Bibliothek.</p>
          <div className="button-row"><button onClick={() => downloadBackup(data)}>Sicherung exportieren</button><button onClick={() => input.current.click()}>Sicherung importieren</button></div>
          <input ref={input} type="file" accept=".json,application/json" hidden onChange={restore} /></>}
        {error && <p role="status" className="error">{error}</p>}
      </PagedPanel>
    </div>
  </section>
}
