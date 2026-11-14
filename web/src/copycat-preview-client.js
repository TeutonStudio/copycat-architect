const abortError = () => Object.assign(new Error('Die Copycat-Auswahl wurde geschlossen.'), { name: 'AbortError' })

// A dialog owns one worker, at most one compilation, and a small LRU cache.
// Content-based keys prevent old previews after replacing a saved form in place.
export function createCopycatPreviewClient({
  workerFactory = () => new Worker(new URL('./copycat-preview.worker.js', import.meta.url), { type: 'module' }),
  cacheSize = 48,
} = {}) {
  const limit = Math.max(0, Math.min(128, Math.floor(cacheSize) || 0))
  const cache = new Map()
  const pending = new Map()
  const queue = []
  let worker, active, disposed = false, fatalError, sequence = 0

  const remember = (key, plan) => {
    if (!limit) return
    cache.delete(key)
    cache.set(key, plan)
    while (cache.size > limit) cache.delete(cache.keys().next().value)
  }
  const stopWorker = () => {
    if (!worker) return
    worker.onmessage = worker.onerror = worker.onmessageerror = null
    worker.terminate()
    worker = null
  }
  const failAll = error => {
    for (const job of pending.values()) job.reject(error)
    pending.clear()
    queue.length = 0
    active = null
    stopWorker()
  }
  const finish = (error, plan) => {
    const job = active
    active = null
    pending.delete(job.key)
    if (error) job.reject(error)
    else {
      remember(job.key, plan)
      job.resolve(plan)
    }
    queueMicrotask(pump)
  }
  const pump = () => {
    if (disposed || fatalError || active || !queue.length) return
    if (!worker) {
      try {
        worker = workerFactory()
        worker.onmessage = ({ data }) => {
          if (disposed || !active || data.id !== active.id) return
          finish(data.error ? new Error(data.error) : null, data.plan)
        }
        worker.onerror = event => {
          event.preventDefault?.()
          fatalError = new Error(event.message || 'Die Copycat-Vorschau konnte nicht berechnet werden.')
          failAll(fatalError)
        }
        worker.onmessageerror = () => {
          fatalError = new Error('Die Copycat-Vorschau konnte nicht gelesen werden.')
          failAll(fatalError)
        }
      } catch (error) {
        fatalError = error
        failAll(error)
        return
      }
    }
    active = queue.shift()
    try {
      worker.postMessage({ id: active.id, option: active.option })
    } catch (error) {
      finish(error)
    }
  }

  return {
    request(option, { priority = false } = {}) {
      if (disposed) return Promise.reject(abortError())
      let key, snapshot
      try {
        const data = { document: option.document, materials: option.materials || {},
          ...(option.referenceOption ? { referenceOption:true, library:option.library, output:option.output } : {}) }
        key = JSON.stringify(data)
        if (cache.has(key)) {
          const plan = cache.get(key)
          remember(key, plan)
          return Promise.resolve(plan)
        }
        if (fatalError) return Promise.reject(fatalError)
        const existing = pending.get(key)
        if (existing) {
          if (priority && existing !== active) {
            queue.splice(queue.indexOf(existing), 1)
            queue.unshift(existing)
          }
          return existing.promise
        }
        snapshot = structuredClone(data)
      } catch (error) {
        return Promise.reject(error)
      }
      const job = { id: ++sequence, key, option: snapshot }
      job.promise = new Promise((resolve, reject) => { job.resolve = resolve; job.reject = reject })
      pending.set(key, job)
      if (priority) queue.unshift(job)
      else queue.push(job)
      pump()
      return job.promise
    },
    dispose() {
      if (disposed) return
      disposed = true
      failAll(abortError())
      cache.clear()
    },
  }
}
