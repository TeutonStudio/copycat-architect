import { PagedPanel } from './PagedPanel.jsx'
import { useEffect, useMemo, useRef, useState } from 'react'
import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { useWorkspace } from './Workspace.jsx'
import { axisColors, sides } from './orientation.js'

const colors = {
  body: '#8ba4a2', cap: '#d29763', 'minecraft:stone_bricks': '#979999',
  'minecraft:deepslate_tiles': '#434e59', 'minecraft:oak_planks': '#bd9163',
  'minecraft:glass': '#a6dceb', 'minecraft:stone': '#a1a6ad',
  'minecraft:spruce_planks': '#846247', 'minecraft:bricks': '#aa6557'
}
function color(value) {
  const id = value.split('[')[0]
  if (colors[id]) return colors[id]
  let hash = 0
  for (const char of id) hash = Math.imul(hash, 31) + char.charCodeAt(0) | 0
  return new THREE.Color().setHSL((hash >>> 0) % 360 / 360, 0.32, 0.59)
}

function dispose(group) {
  const geometries = new Set()
  const materials = new Set()
  group.traverse(item => {
    if (item.geometry) geometries.add(item.geometry)
    for (const material of Array.isArray(item.material) ? item.material : item.material ? [item.material] : []) materials.add(material)
    if (item.isInstancedMesh) item.dispose()
  })
  for (const geometry of geometries) geometry.dispose()
  for (const material of materials) { material.map?.dispose(); material.dispose() }
}

function referenceFrame(group) {
  const bounds = new THREE.Box3(new THREE.Vector3(), new THREE.Vector3(1, 1, 1))
  const size = bounds.getSize(new THREE.Vector3()).max(new THREE.Vector3(1, 1, 1))
  bounds.max.copy(bounds.min).add(size)
  const geometry = new THREE.CylinderGeometry(1, 1, 1, 8)
  const material = (color, opacity) => new THREE.MeshBasicMaterial({ color, opacity, transparent: true, depthTest: false, depthWrite: false, toneMapped: false })
  const surfaces = ['x', 'y', 'z'].map(axis => ({
    front: material(axisColors[axis], 0.95), back: material(axisColors[axis], 0.42)
  }))
  const markFront = material('#ff8b2d', 0.98)
  const markBack = material('#ff8b2d', 0.38)
  const cylinder = (position, direction, length, surface) => {
    const mesh = new THREE.Mesh(geometry, surface)
    mesh.position.copy(position)
    mesh.quaternion.setFromUnitVectors(new THREE.Vector3(0, 1, 0), direction)
    mesh.scale.set(0.004, length, 0.004)
    mesh.renderOrder = 3
    group.add(mesh)
    return mesh
  }
  const edges = []
  for (let axis = 0; axis < 3; axis++) {
    const { front, back } = surfaces[axis]
    const others = [0, 1, 2].filter(value => value !== axis)
    const direction = new THREE.Vector3().setComponent(axis, 1)
    for (const first of [0, 1]) for (const second of [0, 1]) {
      const start = bounds.min.clone()
      start.setComponent(others[0], first ? bounds.max.getComponent(others[0]) : bounds.min.getComponent(others[0]))
      start.setComponent(others[1], second ? bounds.max.getComponent(others[1]) : bounds.min.getComponent(others[1]))
      const center = start.clone().addScaledVector(direction, size.getComponent(axis) / 2)
      const mesh = cylinder(center, direction, size.getComponent(axis), front)
      const marks = [0.25, 0.5, 0.75].map(fraction => {
        const position = start.clone().addScaledVector(direction, size.getComponent(axis) * fraction)
        return { mesh: cylinder(position, new THREE.Vector3(0, 1, 0), 0.05, markFront), major: fraction === 0.5 }
      })
      edges.push({ mesh, marks, direction, front, back, faces: [{ axis: others[0], sign: first ? 1 : -1 }, { axis: others[1], sign: second ? 1 : -1 }] })
    }
  }
  group.userData.reference = { edges, markFront, markBack }
  return bounds
}

function updateReferenceFrame(group, camera, height) {
  const reference = group.userData.reference
  if (!reference) return
  const unitsPerPixel = point => 2 * camera.position.distanceTo(point) * Math.tan(camera.fov * Math.PI / 360) / Math.max(height, 1)
  const up = new THREE.Vector3(0, 1, 0)
  for (const edge of reference.edges) {
    const visible = edge.faces.some(face => face.sign * (camera.position.getComponent(face.axis) - edge.mesh.position.getComponent(face.axis)) > 0)
    edge.mesh.material = visible ? edge.front : edge.back
    edge.mesh.renderOrder = visible ? 3 : 2
    const radius = unitsPerPixel(edge.mesh.position) * (visible ? 0.9 : 0.55)
    edge.mesh.scale.x = radius
    edge.mesh.scale.z = radius
    for (const mark of edge.marks) {
      const direction = edge.direction.clone().cross(camera.position.clone().sub(mark.mesh.position))
      if (direction.lengthSq() < 0.00001) direction.copy(edge.direction).cross(camera.up)
      if (direction.lengthSq() < 0.00001) direction.set(1, 0, 0)
      direction.normalize()
      const scale = unitsPerPixel(mark.mesh.position)
      const width = scale * (mark.major ? 1.4 : 0.65)
      mark.mesh.scale.set(width, scale * (mark.major ? 17 : 8), width)
      mark.mesh.quaternion.setFromUnitVectors(up, direction)
      mark.mesh.material = visible ? reference.markFront : reference.markBack
      mark.mesh.renderOrder = visible ? 5 : 4
    }
  }
}

function groundCompass(group, bounds) {
  if (bounds.isEmpty()) return
  const center = bounds.getCenter(new THREE.Vector3())
  const size = bounds.getSize(new THREE.Vector3())
  const diameter = Math.max(size.x, size.z, 1) * 2.5
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 1024
  const context = canvas.getContext('2d')
  context.translate(512, 512)
  context.strokeStyle = '#8fbbb4'; context.fillStyle = '#d9eee9'; context.lineWidth = 7
  context.beginPath(); context.arc(0, 0, 440, 0, Math.PI * 2); context.stroke()
  for (const [label, x, z] of [['Nord', 0, -1], ['Ost', 1, 0], ['Süd', 0, 1], ['West', -1, 0]]) {
    context.beginPath(); context.moveTo(x * 255, z * 255); context.lineTo(x * 300, z * 300); context.stroke()
  }
  context.fillStyle = '#eead7f'
  context.beginPath(); context.moveTo(0, -325); context.lineTo(-18, -280); context.lineTo(18, -280); context.closePath(); context.fill()
  const texture = new THREE.CanvasTexture(canvas)
  texture.colorSpace = THREE.SRGBColorSpace
  const plane = new THREE.Mesh(new THREE.PlaneGeometry(diameter, diameter), new THREE.MeshBasicMaterial({ map: texture, transparent: true, side: THREE.DoubleSide, depthWrite: false, toneMapped: false }))
  plane.rotation.x = -Math.PI / 2
  plane.position.set(center.x, bounds.min.y - Math.max(size.length() * 0.03, 0.08), center.z)
  group.add(plane)
  const labels = []
  for (const [label, x, z] of [['Nord', 0, -1], ['Ost', 1, 0], ['Süd', 0, 1], ['West', -1, 0]]) {
    const text = document.createElement('canvas')
    text.width = 384; text.height = 160
    const ink = text.getContext('2d')
    ink.fillStyle = '#d9eee9'; ink.font = 'bold 112px system-ui'
    ink.textAlign = 'center'; ink.textBaseline = 'middle'; ink.fillText(label, 192, 80)
    const map = new THREE.CanvasTexture(text)
    map.colorSpace = THREE.SRGBColorSpace
    const mesh = new THREE.Mesh(new THREE.PlaneGeometry(diameter * 384 / 1024, diameter * 160 / 1024), new THREE.MeshBasicMaterial({ map, transparent: true, side: THREE.DoubleSide, depthWrite: false, toneMapped: false }))
    mesh.rotation.x = -Math.PI / 2
    mesh.position.set(center.x + x * diameter * 360 / 1024, plane.position.y + 0.002, center.z + z * diameter * 360 / 1024)
    group.add(mesh); labels.push(mesh)
  }
  group.userData.labels = labels
  group.userData.bounds = new THREE.Box3(
    new THREE.Vector3(center.x - diameter * 0.55, plane.position.y, center.z - diameter * 0.55),
    new THREE.Vector3(center.x + diameter * 0.55, plane.position.y, center.z + diameter * 0.55))
}

export function Viewer({ plan, blockFrame, catalog, details }) {
  const container = useRef(null)
  const viewer = useRef(null)
  const [activeSide, setActiveSide] = useState('')
  const [error, setError] = useState('')
  const [textureWarning, setTextureWarning] = useState('')
  useEffect(() => {
    const element = container.current
    let renderer
    try {
      renderer = new THREE.WebGLRenderer({ antialias: true })
    } catch {
      setError('WebGL 2 steht in diesem Browser nicht zur Verfügung.')
      return
    }
    renderer.setPixelRatio(Math.min(window.devicePixelRatio, 1.5))
    element.append(renderer.domElement)
    renderer.domElement.setAttribute('aria-label', 'Drehbare 3D-Vorschau')
    const scene = new THREE.Scene()
    scene.background = new THREE.Color('#131c23')
    const camera = new THREE.PerspectiveCamera(42, 1, 0.01, 4096)
    camera.position.set(4, 3, 5)
    const controls = new OrbitControls(camera, renderer.domElement)
    const render = () => {
      updateReferenceFrame(guides, camera, element.clientHeight)
      // Rotate only the lettering around world Y; compass anchors stay fixed.
      const groundCenter = ground.userData.bounds?.getCenter(new THREE.Vector3())
      if (groundCenter) {
        const offset = camera.position.clone().sub(groundCenter)
        if (Math.hypot(offset.x, offset.z) > 0.001) {
          for (const label of ground.userData.labels ?? []) {
            const direction = camera.position.clone().sub(label.position)
            const yaw = new THREE.Quaternion().setFromAxisAngle(new THREE.Vector3(0, 1, 0), Math.atan2(direction.x, direction.z))
            label.rotation.set(-Math.PI / 2, 0, 0)
            label.quaternion.premultiply(yaw)
          }
        }
      }
      renderer.render(scene, camera)
    }
    controls.addEventListener('change', render)
    controls.addEventListener('start', () => setActiveSide(''))
    scene.add(new THREE.HemisphereLight('#e8f5ff', '#525764', 2.35))
    const sun = new THREE.DirectionalLight('#fff2d8', 2.1)
    sun.position.set(4, 7, 3)
    scene.add(sun)
    const model = new THREE.Group()
    scene.add(model)
    const guides = new THREE.Group()
    scene.add(guides)
    const ground = new THREE.Group()
    scene.add(ground)
    let measured = false
    const resize = new ResizeObserver(() => {
      const { clientWidth: width, clientHeight: height } = element
      if (!width || !height) return
      const previousAspect = camera.aspect
      camera.aspect = width / height
      camera.updateProjectionMatrix()
      renderer.setSize(width, height, false)
      if (!measured) {
        measured = true
        fit()
      } else {
        // Preserve the viewing direction and zoom when a narrow panel changes
        // the limiting field of view, including a phone rotated sideways.
        camera.position.sub(controls.target).multiplyScalar(Math.min(1, previousAspect) / Math.min(1, camera.aspect)).add(controls.target)
        controls.update()
      }
      render()
    })
    resize.observe(element)
    viewer.current = { model, guides, ground, camera, controls, render, bounds: null }
    return () => {
      resize.disconnect()
      controls.dispose()
      dispose(model)
      dispose(guides)
      dispose(ground)
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
      viewer.current = null
    }
  }, [])

  const fit = (side = null) => {
    const view = viewer.current
    if (!view?.bounds || view.bounds.isEmpty()) return
    const fitBounds = view.bounds.clone()
    if (!side && view.ground.userData.bounds) fitBounds.union(view.ground.userData.bounds)
    const center = fitBounds.getCenter(new THREE.Vector3())
    const radius = Math.max(fitBounds.getSize(new THREE.Vector3()).length() / 2, 0.75)
    const angle = Math.min(Math.tan(view.camera.fov * Math.PI / 360), Math.tan(view.camera.fov * Math.PI / 360) * view.camera.aspect)
    view.controls.target.copy(center)
    view.camera.up.set(...(side?.up ?? [0, 1, 0]))
    view.camera.position.copy(center).addScaledVector(new THREE.Vector3(...(side?.direction ?? [1, 0.72, 1.1])).normalize(), radius / angle * 1.08)
    view.fitted = true
    setActiveSide(side?.id ?? '')
    view.controls.update()
    view.render()
  }

  useEffect(() => {
    const view = viewer.current
    if (!view) return
    let live = true
    setTextureWarning('')
    dispose(view.model)
    view.model.clear()
    const batches = new Map()
    for (const mesh of plan?.meshes ?? []) {
      const list = batches.get(mesh.material) ?? []
      list.push(mesh)
      batches.set(mesh.material, list)
    }
    const matrix = new THREE.Matrix4()
    const scale = new THREE.Vector3()
    const translation = new THREE.Vector3()
    const bounds = new THREE.Box3()
    const corner = new THREE.Vector3()
    for (const [material, meshes] of batches) {
      const count = meshes.reduce((sum, mesh) => sum + mesh.boxes.length * mesh.positions.length, 0)
      if (!count) continue
      const surfaceMaterials = Array.from({ length: 6 }, () => {
        const surface = new THREE.MeshLambertMaterial({ color: color(material), alphaTest: 0.1 })
        surface.onBeforeCompile = shader => {
          shader.vertexShader = 'attribute vec3 blockCenter;\nattribute vec3 blockSize;\n' + shader.vertexShader
          shader.vertexShader = shader.vertexShader.replace('#include <uv_vertex>', `#include <uv_vertex>
            #ifdef USE_MAP
              vec3 pointInBlock = position * blockSize + blockCenter;
              vec2 blockUv = normal.x > 0.5 ? vec2(1.0-pointInBlock.z, pointInBlock.y)
                : normal.x < -0.5 ? vec2(pointInBlock.z, pointInBlock.y)
                : normal.y > 0.5 ? vec2(pointInBlock.x, 1.0-pointInBlock.z)
                : normal.y < -0.5 ? vec2(pointInBlock.x, pointInBlock.z)
                : normal.z > 0.5 ? vec2(pointInBlock.x, pointInBlock.y) : vec2(1.0-pointInBlock.x, pointInBlock.y);
              vMapUv = (mapTransform * vec3(blockUv, 1.0)).xy;
            #endif`)
        }
        surface.customProgramCacheKey = () => 'copycat-block-uv-v1'
        return surface
      })
      const geometry = new THREE.BoxGeometry()
      const blockCenters = new Float32Array(count * 3), blockSizes = new Float32Array(count * 3)
      geometry.setAttribute('blockCenter', new THREE.InstancedBufferAttribute(blockCenters, 3))
      geometry.setAttribute('blockSize', new THREE.InstancedBufferAttribute(blockSizes, 3))
      const draw = new THREE.InstancedMesh(geometry, surfaceMaterials, count)
      let index = 0
      for (const mesh of meshes) for (const [px, py, pz] of mesh.positions) {
        for (const [x, y, z, maxX, maxY, maxZ] of mesh.boxes) {
          scale.set((maxX - x) / 16, (maxY - y) / 16, (maxZ - z) / 16)
          translation.set(px + (x + maxX) / 32, py + (y + maxY) / 32, pz + (z + maxZ) / 32)
          matrix.makeScale(scale.x, scale.y, scale.z).setPosition(translation)
          blockCenters.set([(x + maxX) / 32, (y + maxY) / 32, (z + maxZ) / 32], index * 3)
          blockSizes.set([scale.x, scale.y, scale.z], index * 3)
          draw.setMatrixAt(index++, matrix)
          bounds.expandByPoint(corner.set(px + x / 16, py + y / 16, pz + z / 16))
          bounds.expandByPoint(corner.set(px + maxX / 16, py + maxY / 16, pz + maxZ / 16))
        }
      }
      draw.instanceMatrix.needsUpdate = true
      draw.computeBoundingSphere()
      view.model.add(draw)
      if (catalog.ids.includes(material.split('[')[0])) catalog.resolve(material).then(resolved => {
        if (!live) return
        if (resolved.missing.length || resolved.warning) setTextureWarning(resolved.warning || `Fehlende Texturen: ${resolved.missing.join(', ')}`)
        const textureByUrl = new Map()
        resolved.faces.forEach((url, face) => {
          if (!url) return
          const surface = surfaceMaterials[face]
          let texture = textureByUrl.get(url)
          if (!texture) {
            texture = new THREE.TextureLoader().load(url, loaded => {
              if (!live) { loaded.dispose(); return }
              loaded.colorSpace = THREE.SRGBColorSpace
              loaded.magFilter = THREE.NearestFilter
              loaded.minFilter = THREE.NearestMipmapNearestFilter
              const fraction = Math.min(1, loaded.image.width / loaded.image.height)
              loaded.repeat.y = fraction; loaded.offset.y = 1 - fraction
              loaded.needsUpdate = true
              view.render()
            }, undefined, () => { if (live) {
              for (const surface of surfaceMaterials) if (surface.map === texture) { surface.map = null; surface.color.set(color(material)); surface.needsUpdate = true }
              setTextureWarning('Eine Textur konnte nicht geladen werden; ihre Materialfarbe bleibt sichtbar.'); view.render()
            } })
            textureByUrl.set(url, texture)
          }
          surface.map = texture; surface.color.set('#ffffff'); surface.needsUpdate = true
        })
        view.render()
      }).catch(error => { if (live) setTextureWarning(error.message) })
    }
    dispose(view.guides)
    view.guides.clear()
    const size = bounds.getSize(new THREE.Vector3())
    const singleBlock = bounds.isEmpty() || (bounds.min.x >= 0 && bounds.min.y >= 0 && bounds.min.z >= 0 && bounds.max.x <= 1 && bounds.max.y <= 1 && bounds.max.z <= 1 && Math.max(size.x, size.y, size.z) <= 1)
    view.guides.userData.reference = null
    view.bounds = blockFrame && singleBlock ? referenceFrame(view.guides) : bounds
    dispose(view.ground); view.ground.clear(); view.ground.userData.bounds = null
    groundCompass(view.ground, view.bounds)
    if (!view.fitted || plan && !view.hadGeometry) fit()
    view.hadGeometry = Boolean(plan)
    view.render()
    return () => { live = false }
  }, [plan, blockFrame, catalog])

  const hasDetails = !!details
  return <div className={`viewer-with-sides${hasDetails ? ' has-details' : ''}`}>
    <aside className="preview-hud preview-hud-left" aria-label="3D-Ansichten"><PagedPanel label="Ansichten">
      <div className="preview-sides" role="group" aria-label="Seitenansicht wählen">
        {sides.map(side => <button key={side.id} type="button" aria-pressed={activeSide === side.id}
          aria-label={`Ansicht ${side.label}`} onClick={() => fit(side)}>{side.label}</button>)}
      </div>
      <button type="button" className="fit3d" aria-label="3D einpassen" onClick={() => fit()}>Einpassen</button>
    </PagedPanel></aside>
    <div className="viewer" ref={container}>
      {!hasDetails && error && <p className="preview-error" role="status">{error}</p>}
      {!hasDetails && textureWarning && <p className="texture-warning" role="status">{textureWarning}</p>}
    </div>
    {hasDetails && <aside className="preview-hud preview-hud-right" aria-label="3D-Informationen"><PagedPanel label="Details">
      {details}
      {error && <p className="preview-error" role="status">{error}</p>}
      {textureWarning && <p className="texture-warning" role="status">{textureWarning}</p>}
    </PagedPanel></aside>}
  </div>
}

export default function Preview({ mode, document, library, selected, previewMode, onMode, collapsed, onToggle, materials }) {
  const { catalog } = useWorkspace()
  const worker = useRef(null)
  const sequence = useRef(0)
  const running = useRef(false)
  const queued = useRef(null)
  const [result, setResult] = useState({})
  const target = previewMode === 'selected' ? selected : ''
  const missingSelection = previewMode === 'selected' && !selected
  const blockFrame = mode === 'form' || (previewMode === 'selected' && document.graph.nodes.find(node => node.id === selected)?.kind?.startsWith('COPYCAT'))
  const latest = useRef(document)
  latest.current = document
  useEffect(() => { setResult({}) }, [mode, target, previewMode])
  const content = useMemo(() => JSON.stringify({
    ...document, graph: { ...document.graph, nodes: document.graph.nodes.map(({ x, y, ...node }) => node) }
  }), [document])
  const send = () => {
    if (!worker.current || running.current || !queued.current) return
    running.current = true
    worker.current.postMessage(queued.current)
    queued.current = null
  }
  useEffect(() => {
    const instance = new Worker(new URL('./preview.worker.js', import.meta.url), { type: 'module' })
    worker.current = instance
    instance.onmessage = ({ data }) => {
      running.current = false
      if (data.id === sequence.current) {
        setResult(previous => data.error ? { plan: previous.plan, error: data.error, pending: false } : data)
      }
      send()
    }
    instance.onerror = event => {
      running.current = false
      setResult(previous => ({ ...previous, error: event.message, pending: false }))
    }
    return () => instance.terminate()
  }, [])

  useEffect(() => {
    const id = ++sequence.current
    queued.current = null
    if (missingSelection) {
      setResult({ error: 'Wähle einen Knoten im Graphen aus.', pending: false })
      return
    }
    const timer = setTimeout(() => {
      setResult(previous => ({ ...previous, pending: true, error: '' }))
      queued.current = { id, mode, document: latest.current, library, selected: target }
      send()
    }, 130)
    return () => clearTimeout(timer)
  }, [mode, content, library, target, missingSelection])


  const noun = mode === 'building' ? 'Platzierungen' : 'Rasterzellen'
  const texturedPlan = useMemo(() => result.plan && mode === 'form' ? { ...result.plan, meshes: result.plan.meshes.map(mesh => ({ ...mesh, material: materials[mesh.material] || mesh.material })) } : result.plan, [result.plan, mode, materials])
  return <section className="preview-panel" aria-label="Gebäude- und Formvorschau">
    <div className="panel-heading"><span>3D-Vorschau</span><div className="button-row">
      <div className="preview-target-switch" role="group" aria-label="Vorschauziel">
        {[['graph', 'Graph'], ['selected', 'Selektiert']].map(([value, label]) =>
          <button key={value} type="button" aria-pressed={previewMode === value}
            onClick={() => onMode(value)}>{label}</button>)}
      </div>
      <button type="button" aria-expanded={!collapsed} aria-controls={`${mode}-preview`} aria-label={collapsed ? '3D-Vorschau ausklappen' : '3D-Vorschau einklappen'} onClick={onToggle}>{collapsed ? '▸' : '▾'}</button>
    </div></div>
    <div id={`${mode}-preview`} className="preview-body" hidden={collapsed}>
    <Viewer plan={texturedPlan} blockFrame={blockFrame} catalog={catalog} details={<>
    <div className="preview-meta" role="status">
      {result.error ? <><strong>{result.plan ? 'Letzter gültiger Stand' : 'Vorschau wartet auf einen gültigen Graphen'}</strong><br />{result.error}</>
        : result.pending ? 'Vorschau wird berechnet …'
          : result.plan ? <>{result.plan.count.toLocaleString('de-DE')} {noun} · {result.plan.raw.toLocaleString('de-DE')} Zinkeinheiten</>
            : 'Vorschau wird geladen …'}
    </div>
    {result.plan?.value && <p className="preview-meta">{result.plan.value}</p>}
    {result.plan?.warnings?.map((warning, index) => <p className="preview-meta" key={index}>{warning}</p>)}
    <div className="axis-legend" aria-label="Koordinatenachsen"><span style={{color:axisColors.x}}>West (−X) · Ost (+X)</span><span style={{color:axisColors.y}}>Unten (−Y) · Oben (+Y)</span><span style={{color:axisColors.z}}>Nord (−Z) · Süd (+Z)</span>
      {blockFrame && <span>Rahmen: 16 hB · lange Marke: 8 hB · kurze Marken: 4 / 12 hB</span>}</div>
    {result.plan?.materials && <details className="materials"><summary>Materialbedarf</summary>
      {Object.entries(result.plan.materials).map(([id, count]) => <div key={id}><code>{id}</code><span>{count}</span></div>)}
    </details>}
    </>} />
    </div>
  </section>
}
