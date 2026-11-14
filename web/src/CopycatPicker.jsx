import { useEffect, useId, useMemo, useRef, useState } from 'react'
import { Viewer } from './Preview.jsx'
import { useWorkspace } from './Workspace.jsx'
import { listReferenceOptions, prepareReferenceOption } from './reference-options.js'
import { usePageSize } from './PagedPanel.jsx'
import { createCopycatPreviewClient } from './copycat-preview-client.js'
import { createCopycatThumbnailRenderer } from './copycat-thumbnails.js'

const sources = { graph: 'Gespeicherter Graph', group: 'Gruppe / Karte', template: 'Architekturvorlage', empty: 'Grundform', base: 'Grundform', saved: 'Meine gespeicherten Formen', library: 'Formbibliothek' }
const categories = [['all', 'Alle Quellen'], ['basic', 'Grundformen'], ['saved', 'Gespeicherte Formen'], ['graphs', 'Graphen'], ['groups', 'Gruppen / Karten'], ['templates', 'Vorlagen']]

function createPreviews() {
  const client = createCopycatPreviewClient()
  const images = new Map()
  let renderer, renderError, closed = false
  return {
    request: (option, settings) => client.request(option, settings),
    async thumbnail(option) {
      if (images.has(option)) return images.get(option)
      const plan = await client.request(option)
      if (closed) throw Object.assign(new Error('Die Auswahl wurde geschlossen.'), { name: 'AbortError' })
      if (renderError) throw renderError
      try {
        renderer ??= createCopycatThumbnailRenderer()
        const image = renderer.render(plan)
        images.set(option, image)
        if (images.size > 80) images.delete(images.keys().next().value)
        return image
      } catch (error) {
        renderError = error
        throw error
      }
    },
    dispose() {
      closed = true
      client.dispose()
      renderer?.dispose()
      images.clear()
    },
  }
}

function ShapeCard({ option, selected, previews, onSelect }) {
  const card = useRef(null)
  const [visible, setVisible] = useState(false)
  const [thumbnail, setThumbnail] = useState(null)
  useEffect(() => {
    if (!window.IntersectionObserver) { setVisible(true); return }
    const observer = new IntersectionObserver(entries => {
      if (entries.some(entry => entry.isIntersecting)) { setVisible(true); observer.disconnect() }
    }, { rootMargin: '80px' })
    observer.observe(card.current)
    return () => observer.disconnect()
  }, [])
  useEffect(() => {
    if (!visible || !previews) return
    let live = true
    setThumbnail(null)
    previews.thumbnail(option).then(image => { if (live) setThumbnail({ image }) })
      .catch(error => { if (live && error.name !== 'AbortError') setThumbnail({ error: error.message || String(error) }) })
    return () => { live = false }
  }, [option, visible, previews])
  return <button ref={card} type="button" className="copycat-card" aria-pressed={selected}
    aria-label={`${option.name} · ${sources[option.kind]}${option.reference ? ` · ${option.reference}` : ''}`}
    onClick={() => onSelect(option.id)}>
    <span className="copycat-thumbnail" aria-hidden="true">
      {thumbnail?.image ? <img src={thumbnail.image} alt="" draggable="false" />
        : <span className={thumbnail?.error ? 'error' : ''} title={thumbnail?.error}>
          {thumbnail?.error ? 'Vorschau nicht verfügbar' : previews ? 'Vorschau …' : sources[option.kind]}
        </span>}
      {selected && <span className="copycat-check">✓</span>}
    </span>
    <strong>{option.name}</strong>
    <small>{option.kind === 'library' ? option.reference : sources[option.kind]}</small>
  </button>
}

export default function CopycatPicker({ mode, savedForms, savedGraphs, savedGroups, library, initialId, initialCategory = 'all', onChoose, onClose }) {
  const { catalog } = useWorkspace()
  const dialog = useRef(null)
  const id = useId()
  const [category, setCategory] = useState(initialCategory)
  const [query, setQuery] = useState('')
  const [page, setPage] = useState(0)
  const [path, setPath] = useState([])
  const [output, setOutput] = useState('')
  const [detailTab, setDetailTab] = useState('catalog')
  const [listRef, pageSize] = usePageSize(145, 150)
  const setLimit = () => setPage(0)
  const [chosenId, setChosenId] = useState(initialId || null)
  const [previews, setPreviews] = useState(null)
  const [result, setResult] = useState(null)
  const [error, setError] = useState('')
  const [applying, setApplying] = useState(false)
  const options = useMemo(() => listReferenceOptions({ mode, savedForms, savedGraphs, savedGroups, library }), [mode, savedForms, savedGraphs, savedGroups, library])
  const rawChosen = options.find(option => option.id === chosenId)
  const prepared = useMemo(() => { try { return { option: rawChosen && prepareReferenceOption(rawChosen, output) } } catch(error) { return { error:error.message } } }, [rawChosen, output])
  const chosen = prepared.option
  const words = query.toLocaleLowerCase('de').trim().split(/\s+/).filter(Boolean)
  const filtered = options.filter(option => (category === 'all' || option.category === category) && words.every(word =>
    `${option.name} ${option.source || ''} ${option.reference || ''}`.toLocaleLowerCase('de').includes(word)))
  const inFolder = filtered.filter(option => query || option.path.slice(0, path.length).join('/') === path.join('/'))
  const folders = query ? [] : [...new Set(inFolder.filter(o => o.path.length > path.length + 1).map(o => o.path[path.length]))]
  const leaves = inFolder.filter(o => query || o.path.length === path.length + 1)
  const items = [...folders.map(folder => ({ folder })), ...leaves]
  const pages = Math.max(1, Math.ceil(items.length / pageSize))
  const shown = items.slice(Math.min(page, pages - 1) * pageSize, (Math.min(page, pages - 1) + 1) * pageSize)
  const ready = result?.option === chosen && result?.plan
  const previewError = result?.option === chosen && result?.error

  useEffect(() => {
    const element = dialog.current
    const opener = document.activeElement
    const overflow = document.body.style.overflow
    element.showModal()
    document.body.style.overflow = 'hidden'
    return () => {
      element.close()
      document.body.style.overflow = overflow
      if (opener?.isConnected) opener.focus({ preventScroll: true })
    }
  }, [])
  useEffect(() => {
    const services = createPreviews()
    setPreviews(services)
    return () => services.dispose()
  }, [])
  useEffect(() => {
    setResult(null)
    setError('')
    if (!chosen || !previews) return
    let live = true
    previews.request(chosen, { priority: true }).then(plan => { if (live) setResult({ option: chosen, plan }) })
      .catch(error => { if (live && error.name !== 'AbortError') setResult({ option: chosen, error: error.message || String(error) }) })
    return () => { live = false }
  }, [chosen, previews])

  const switchCategory = next => {
    setCategory(next); setPath([]); setQuery(''); setPage(0); setChosenId(null); setError(''); setOutput('')
  }
  const choose = async () => {
    if (!chosen || !ready || applying) return
    setApplying(true); setError('')
    try { await onChoose(chosen); onClose() }
    catch (error) { setError(error.message || String(error)); setApplying(false) }
  }
  const keydown = event => {
    event.stopPropagation()
    if (event.key !== 'Tab') return
    const controls = [...dialog.current.querySelectorAll('button, input, select, textarea, a[href], [tabindex]')]
      .filter(element => !element.disabled && element.tabIndex >= 0 && element.getClientRects().length
        && getComputedStyle(element).visibility !== 'hidden')
    const target = event.shiftKey ? controls.at(-1) : controls[0]
    if (document.activeElement === (event.shiftKey ? controls[0] : controls.at(-1)) || !dialog.current.contains(document.activeElement)) {
      event.preventDefault()
      target?.focus()
    }
  }

  return <dialog ref={dialog} className="copycat-dialog" data-detail={detailTab} aria-labelledby={`${id}-title`} aria-describedby={`${id}-description`}
    onCancel={event => { event.preventDefault(); if (!applying) onClose() }} onKeyDown={keydown}>
    <div className="copycat-dialog-layout">
      <header className="copycat-dialog-heading">
        <div><span className="copycat-eyebrow">REFERENZBIBLIOTHEK</span><h2 id={`${id}-title`}>{mode === 'building' ? 'Referenz auswählen' : 'Copycat auswählen'}</h2>
          <p id={`${id}-description`}>Quelle ansehen und mit „Auswahl übernehmen“ in den Graphen einsetzen.</p></div>
        <button type="button" aria-label="Auswahl schließen" disabled={applying} onClick={onClose}>×</button>
      </header>
      <nav className="reference-view-tabs"><button aria-pressed={detailTab === 'catalog'} onClick={() => setDetailTab('catalog')}>Bibliothek</button><button aria-pressed={detailTab === 'preview'} onClick={() => setDetailTab('preview')}>Vorschau</button></nav>
      <div className="copycat-dialog-body">
        <section className="copycat-catalog" aria-label="Verfügbare Copycats">
          <div className="reference-navigation"><select aria-label="Quellenart" value={category} onChange={e => switchCategory(e.target.value)}>{categories.map(([value,label]) => <option key={value} value={value}>{label}</option>)}</select>
            <button disabled={!path.length} onClick={() => {setPath(path.slice(0,-1));setPage(0)}} aria-label="Übergeordneter Ordner">↑</button><span title={path.join(' / ')}>{path.join(' / ') || 'Bibliothek'}</span></div>
          <label className="copycat-search">Quellen suchen<input type="search" autoFocus autoComplete="off"
            placeholder={category === 'basic' ? 'z. B. Treppe, Stufe oder Panel' : 'Name oder Bibliotheksreferenz'}
            value={query} onChange={event => { setQuery(event.target.value); setLimit(48); setChosenId(null); setError('') }} /></label>
          <div className="copycat-results" ref={listRef} id={`${id}-forms`}>
            <div className="copycat-grid">{shown.map(option => option.folder ? <button type="button" className="copycat-card folder-card" key={`folder:${option.folder}`} onClick={() => {setPath([...path, option.folder]);setPage(0)}}><span aria-hidden="true">▣</span><strong>{option.folder}</strong><small>Ordner öffnen</small></button>
              : <ShapeCard key={option.id} option={{...option,name:option.path.at(-1)}} selected={option.id === chosenId} previews={['graph','group','template'].includes(option.kind) ? null : previews} onSelect={value => {setChosenId(value);setOutput('');setError('')}} />)}</div>
            {!items.length && <p className="hint">Keine passende Quelle gefunden.</p>}
          </div>
          <nav className="page-controls" aria-label="Bibliotheksseiten"><button disabled={!page} onClick={() => setPage(page-1)}>←</button><span>{Math.min(page+1,pages)} / {pages}</span><button disabled={page+1>=pages} onClick={() => setPage(page+1)}>→</button></nav>
        </section>
        <section className={`copycat-detail${chosen ? '' : ' is-empty'}`} aria-label="Vorschau der Auswahl" aria-busy={Boolean(chosen && !ready && !previewError)}>
          {prepared.error && <p className="error" role="alert">{prepared.error}</p>}
          {chosen?.referenceOption && <label className="reference-output">Ergebnisausgang<select aria-label="Ergebnisausgang" value={chosen.output} onChange={e => setOutput(e.target.value)}>{chosen.outputs.map(entry => <option key={entry.node.id} value={entry.node.id}>{entry.node.name || entry.node.kind} · {entry.outputType}</option>)}</select></label>}
          {chosen &&
            <div className="copycat-detail-heading"><span className="copycat-eyebrow">{sources[chosen.kind]}</span><h3>{chosen.name}</h3>
              {chosen.reference && <p>{chosen.reference}</p>}</div>}
          <div className={`copycat-preview-stage${ready ? '' : ' is-pending'}`}>
            <Viewer plan={ready || null} blockFrame={!chosen?.referenceOption} catalog={catalog} />
            {!ready && <div className="copycat-preview-placeholder" role="status">{chosen
              ? previewError ? <p className="error">Diese Form kann nicht geladen werden: {previewError}</p> : <p>3D-Vorschau wird berechnet …</p>
              : <><span className="copycat-placeholder-symbol" aria-hidden="true">◇</span>
                <h3>Eine Form auswählen</h3><p>Klicke auf eine Kachel, um die Form in 3D zu prüfen.</p></>}
            </div>}
          </div>
          {chosen &&
            <div className="copycat-detail-info">
              <p>{chosen.referenceOption ? `${chosen.type} · Version ${chosen.version}` : 'Copycat · 16 hB'}{ready ? ` · ${ready.count.toLocaleString('de-DE')} Rasterzellen` : ''}</p>
              <p>Ziehen zum Drehen · Scrollen zum Zoomen</p>
              {chosen.kind === 'base' ? <p>Grundformen werden in Standardausrichtung eingesetzt. {mode === 'form'
                ? 'Größe und Ausrichtung lassen sich danach im Inspektor ändern.'
                : 'Copycat-Operatoren im Graphen drehen oder spiegeln das Ergebnis.'}</p>
                : <p>Die Auswahl übernimmt die gespeicherte Geometrie mit ihren Materialgruppen.</p>}
            </div>}
        </section>
      </div>
      <footer className="copycat-dialog-footer">
        <div className="copycat-selection-status" role="status">{error ? <span className="error">{error}</span>
          : chosen ? <><strong>{chosen.name}</strong><span> zur Übernahme ausgewählt</span></> : 'Noch keine Quelle ausgewählt'}</div>
        <div className="button-row"><button type="button" disabled={applying} onClick={onClose}>Abbrechen</button>
          <button type="button" className="primary" disabled={!ready || applying} onClick={choose}>{applying ? 'Wird übernommen …' : 'Auswahl übernehmen'}</button></div>
      </footer>
    </div>
  </dialog>
}
