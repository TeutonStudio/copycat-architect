import { useLayoutEffect, useRef, useState } from 'react'

export function usePageSize(width = 160, height = 150) {
  const ref = useRef(null), [count, setCount] = useState(1)
  useLayoutEffect(() => {
    const element = ref.current
    if (!element) return
    const update = () => setCount(Math.max(1, Math.floor(element.clientWidth / width) * Math.max(1, Math.floor(element.clientHeight / height))))
    const observer = new ResizeObserver(update);observer.observe(element);update()
    return () => observer.disconnect()
  }, [width, height])
  return [ref, count]
}

// Paginate actual laid-out controls, including expanded native property sections.
// Hidden pages are inert to keyboard/assistive navigation; no content is dropped.
export function PagedPanel({ children, label = 'Inhalt', className = '', onClose, ...props }) {
  const body = useRef(null), pages = useRef([]), current = useRef(0)
  const [page, setPage] = useState(0), [total, setTotal] = useState(1)
  const apply = index => {
    const element = body.current
    if (!element) return
    const shown = new Set(pages.current[index] || [])
    for (const item of pages.current.flat()) item.classList.toggle('pager-hidden', !shown.has(item))
    const units = new Set(pages.current.flat())
    for (const parent of [...element.querySelectorAll('*')].reverse()) {
      if (units.has(parent) || !parent.querySelector('[data-page-item]')) continue
      parent.classList.toggle('pager-empty', ![...parent.querySelectorAll('[data-page-item]')].some(item => shown.has(item)))
    }
  }
  useLayoutEffect(() => {
    const element = body.current
    if (!element) return
    let frame
    const measure = () => {
      element.querySelectorAll('.pager-hidden,.pager-empty').forEach(item => item.classList.remove('pager-hidden','pager-empty'))
      element.querySelectorAll('[data-page-item]').forEach(item => delete item.dataset.pageItem)
      const units = []
      const collect = parent => {
        for (const item of parent.children) {
          if (item.hidden || item.type === 'hidden') continue
          if (!item.getClientRects().length) { if (getComputedStyle(item).display === 'contents') collect(item);continue }
          if (item.matches('details')) { collect(item);continue }
          if (item.matches('label,summary,h2,h3,h4,p,pre,table,button,select,input,textarea,.button-row,.node-id,.reference-link,.saved-entry,.arrangement-grid,.page-controls') || !item.children.length) {
            if (item.children.length && item.getBoundingClientRect().height > Math.max(40,element.clientHeight - 20) && !item.matches('label,button,select,input,textarea')) collect(item)
            else {units.push(item);item.dataset.pageItem = 'true'}
          } else collect(item)
        }
      }
      collect(element)
      const height = Math.max(24, element.clientHeight - 16)
      const result = [[]];let used = 0
      for (const item of units) {
        const style = getComputedStyle(item)
        const size = Math.min(height, item.getBoundingClientRect().height + parseFloat(style.marginTop || 0) + parseFloat(style.marginBottom || 0) + 8)
        if (used + size > height && result.at(-1).length) { result.push([]);used = 0 }
        result.at(-1).push(item);used += size
      }
      pages.current = result
      current.current = Math.min(current.current, result.length - 1)
      setTotal(result.length);setPage(current.current);apply(current.current)
    }
    const schedule = () => { cancelAnimationFrame(frame);frame = requestAnimationFrame(measure) }
    const resize = new ResizeObserver(schedule);resize.observe(element)
    const mutation = new MutationObserver(schedule);mutation.observe(element, { childList:true, subtree:true, attributes:true, attributeFilter:['open'] })
    measure()
    return () => { resize.disconnect();mutation.disconnect();cancelAnimationFrame(frame) }
  }, [children])
  useLayoutEffect(() => { current.current = page;apply(page) }, [page,total])
  return <div {...props} className={`paged-panel ${className}`}>
    <div className="pager-body" ref={body}>{children}</div>
    <nav className="page-controls" aria-label={`${label}: Seiten`}><button type="button" aria-label={`${label}: vorherige Seite`} disabled={!page} onClick={() => setPage(page-1)}>←</button><span aria-live="polite">{label} · {page+1} / {total}</span><button type="button" aria-label={`${label}: nächste Seite`} disabled={page+1>=total} onClick={() => setPage(page+1)}>→</button>{onClose && <button type="button" onClick={onClose}>Schließen</button>}</nav>
  </div>
}
