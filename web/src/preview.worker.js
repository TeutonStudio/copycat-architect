import { expandReferences } from './references.js'
import { previewGraph } from './generated/engine.mjs'

self.onmessage = ({ data: { id, mode, document, library, selected } }) => {
  try {
    const source = mode === 'building' ? expandReferences(document, library) : { document, library }
    self.postMessage({ id, plan: previewGraph(mode === 'building', source.document, source.library, selected) })
  } catch (error) {
    self.postMessage({ id, error: error.message || String(error) })
  }
}
