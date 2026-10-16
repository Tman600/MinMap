// Car side: the map, search, a route with turn-by-turn, the style menu, and the phone-video panel.
import { el, icon, store, loadScript, toast, fmtDistance, fmtDuration, fmtArrival } from './ui.js'
import { LOOKS, LIGHTS, buildConfig, isDark, resolveLight } from './styles.js'
import * as mb from './mapbox.js'
import { RouteTracker, maneuverIcon, bearing, distance } from './guidance.js'
import { Watch } from './watch.js'

const DEFAULT_SETTINGS = { look: 'Standard', light: 'auto', buildings3d: true, pois: true, metric: false, voice: true }
let settings = { ...DEFAULT_SETTINGS, ...store.get('settings', {}) }

let map, puck, destMarker
let here = null          // [lng, lat]
let heading = 0
let speed = 0
let follow = true
let videoSide = null     // which half the video covers, so the camera keeps the car in view
let watch
let ui = {}
let trip = null          // { place, route, tracker, driving, lastReroute, offCount, spokenStep, spokenNear }

export async function start() {
  const token = store.get('token')
  if (!token) return signIn()
  const css = new Promise(resolve => {
    const link = el('link', { rel: 'stylesheet', href: new URL('../vendor/mapbox-gl.css', import.meta.url).href })
    link.onload = link.onerror = resolve
    document.head.append(link)
  })
  await Promise.all([window.mapboxgl ? null : loadScript(new URL('../vendor/mapbox-gl.js', import.meta.url).href), css])
  buildUi()
  buildMap(token)
  watchLocation()
  setInterval(() => { if (settings.light === 'auto') applyStyle() }, 5 * 60 * 1000)
}

// ---- sign-in ----

function signIn(message) {
  document.body.className = 'signin-page'
  const input = el('input', { class: 'token-input', placeholder: 'pk.eyJ1Ijoi\u2026', autocomplete: 'off', spellcheck: 'false', 'aria-label': 'Mapbox public token' })
  const err = el('p', { class: 'err' }, message || '')
  const go = el('button', { class: 'btn primary wide' }, 'Continue')
  const submit = async () => {
    const t = input.value.trim()
    go.disabled = true; go.textContent = 'Checking\u2026'; err.textContent = ''
    const problem = await mb.checkToken(t)
    go.disabled = false; go.textContent = 'Continue'
    if (problem) { err.textContent = problem; return }
    store.set('token', t)
    location.reload()
  }
  go.addEventListener('click', submit)
  // Typing a token on a car screen is painful: send it from a phone instead (give.js).
  const phoneBox = el('div', { class: 'phone-signin' },
    el('button', { class: 'btn wide', onclick: async () => {
      const { receiveToken } = await import('./give.js?v=22')
      // Only the QR code matters now; the typing box and text would push it off a short car screen.
      phoneBox.closest('.signin')?.classList.add('phone-mode')
      const got = await receiveToken(phoneBox)
      const problem = await mb.checkToken(got.token)
      got.close()
      if (problem) { got.status.className = 'status'; got.status.textContent = problem + ' Reload this page on the car to try again.'; return }
      store.set('token', got.token)
      location.reload()
    } }, 'Sign in with your phone'))
  input.addEventListener('keydown', e => { if (e.key === 'Enter') submit() })
  document.getElementById('app').replaceChildren(el('main', { class: 'signin' },
    el('img', { src: 'icon.svg', alt: '', class: 'signin-logo' }),
    el('h1', {}, 'MinMap'),
    el('p', { class: 'muted' }, 'MinMap draws its map with your own Mapbox account. Paste a public token (it starts with pk.) from account.mapbox.com, under Access tokens.'),
    input, err, go,
    phoneBox,
    el('p', { class: 'fine' }, 'The token stays in this browser. Nothing is sent anywhere except Mapbox.'),
    el('button', { class: 'btn ghost small', onclick: () => { location.hash = '#watch'; location.reload() } }, 'Only watch phone video, no map')))
}

// ---- layout ----

function buildUi() {
  document.body.className = 'car-page'
  ui.search = el('input', { class: 'search-input', type: 'search', placeholder: 'Search', 'aria-label': 'Search for a place', autocomplete: 'off', enterkeyhint: 'search' })
  ui.results = el('div', { class: 'results', hidden: true })
  ui.searchBox = el('div', { class: 'searchbox panel' }, icon('search'), ui.search)
  ui.top = el('div', { class: 'top-left' }, ui.searchBox, ui.results)

  ui.banner = el('div', { class: 'banner panel', hidden: true })
  ui.card = el('div', { class: 'card panel', hidden: true })
  ui.tripBar = el('div', { class: 'tripbar panel', hidden: true })

  ui.recenter = el('button', { class: 'fab', 'aria-label': 'Follow my position', hidden: true, onclick: () => { follow = true; ui.recenter.hidden = true; moveCamera(true) } }, icon('locate'))
  ui.styleBtn = el('button', { class: 'fab', 'aria-label': 'Map style', onclick: toggleStyleMenu }, icon('layers'))
  ui.watchBtn = el('button', { class: 'fab', 'aria-label': 'Watch from phone', onclick: () => watch.toggle() }, icon('screen'))
  ui.fabs = el('div', { class: 'fabs' }, ui.styleBtn, ui.watchBtn, ui.recenter)
  ui.menu = el('div', { class: 'menu panel', hidden: true })

  document.getElementById('app').replaceChildren(
    el('div', { id: 'map' }), ui.top, ui.banner, ui.card, ui.tripBar, ui.fabs, ui.menu)

  watch = new Watch({ onLayout: side => { videoSide = side; document.body.dataset.video = side || ''; moveCamera(true) } })
  wireSearch()
  applyTheme()
}

function applyTheme() {
  document.body.classList.toggle('dark', isDark(settings))
}

// ---- map ----

function buildMap(token) {
  mapboxgl.accessToken = token
  const last = store.get('view', { center: [-98.5, 39.5], zoom: 3.5 })
  map = new mapboxgl.Map({
    container: 'map',
    style: 'mapbox://styles/mapbox/standard',
    config: { basemap: buildConfig(settings) },
    center: last.center,
    zoom: last.zoom,
    pitch: 0,
    attributionControl: true,
    logoPosition: 'bottom-left',
    maxPitch: 70,
  })
  map.on('error', e => {
    const status = e.error?.status
    // Every tile fails the same way, so only the first one signs out.
    if ((status === 401 || status === 403) && map) {
      store.set('token', null)
      map.remove()
      map = null
      watch.close()
      signIn('Mapbox stopped accepting this token (' + status + '). If the account is out of free use, add a card at account.mapbox.com, then sign in again.')
    }
  })
  map.on('load', () => {
    map.addSource('route', { type: 'geojson', data: emptyLine() })
    map.addLayer({
      id: 'route-casing', type: 'line', source: 'route', slot: 'middle',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#0B4FB3', 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 6, 18, 20], 'line-emissive-strength': 1 },
    })
    map.addLayer({
      id: 'route-line', type: 'line', source: 'route', slot: 'middle',
      layout: { 'line-cap': 'round', 'line-join': 'round' },
      paint: { 'line-color': '#2F7BF6', 'line-width': ['interpolate', ['linear'], ['zoom'], 10, 4, 18, 15], 'line-emissive-strength': 1 },
    })
  })
  // A person moving the map stops the camera from following until they tap the locate button.
  for (const ev of ['dragstart', 'rotatestart', 'pitchstart']) {
    map.on(ev, e => { if (e.originalEvent) { follow = false; ui.recenter.hidden = !here } })
  }
  map.on('wheel', () => { follow = false; ui.recenter.hidden = !here })
  map.on('moveend', () => {
    const c = map.getCenter()
    store.set('view', { center: [c.lng, c.lat], zoom: map.getZoom() })
  })

  const puckEl = el('div', { class: 'puck', html: '<svg viewBox="0 0 40 40"><circle cx="20" cy="20" r="17" fill="#fff"/><circle cx="20" cy="20" r="14" fill="#1C1C1E"/><path d="M20 9l7.5 18L20 23l-7.5 4z" fill="#fff"/></svg>' })
  puck = new mapboxgl.Marker({ element: puckEl, rotationAlignment: 'map', pitchAlignment: 'map' })
}

function emptyLine() {
  return { type: 'Feature', geometry: { type: 'LineString', coordinates: [] }, properties: {} }
}

function applyStyle() {
  if (!map) return
  map.setConfig('basemap', buildConfig(settings))
  applyTheme()
}

// ---- location ----

function watchLocation() {
  if (!navigator.geolocation) return
  navigator.geolocation.watchPosition(onFix, err => {
    if (err.code === 1) toast('Location is off for this site, so the map can\u2019t follow the car.')
  }, { enableHighAccuracy: true, maximumAge: 1000, timeout: 20000 })
}

function onFix(p) {
  if (!map) return
  const next = [p.coords.longitude, p.coords.latitude]
  const first = !here
  if (p.coords.heading != null && !isNaN(p.coords.heading) && (p.coords.speed ?? 0) > 1) heading = p.coords.heading
  else if (here && distance(here, next) > 4) heading = bearing(here, next)
  speed = p.coords.speed ?? (here ? speed : 0)
  here = next
  puck.setLngLat(here).setRotation(heading)
  if (first) puck.addTo(map)
  if (trip?.driving) driveStep()
  if (follow) moveCamera(first)
}

// While driving, the car sits low on screen so more road ahead shows; the video half is kept clear.
function cameraPadding(driving) {
  const h = map.getContainer().clientHeight, w = map.getContainer().clientWidth
  const padding = { top: driving ? h * 0.38 : 0, bottom: 0, left: 0, right: 0 }
  if (videoSide) padding[videoSide] = w * 0.5
  return padding
}

function moveCamera(jump) {
  if (!map || !here) return
  const driving = !!trip?.driving
  const padding = cameraPadding(driving)
  const zoom = driving ? (speed > 25 ? 15.6 : speed > 15 ? 16.2 : 16.8) : Math.max(map.getZoom(), 15)
  const opts = {
    center: here, padding, zoom,
    bearing: driving ? heading : map.getBearing(),
    pitch: driving ? 58 : map.getPitch(),
    duration: jump ? 0 : 1000, easing: t => t,
  }
  if (jump) map.jumpTo(opts)
  else map.easeTo({ ...opts, essential: true })
}

// ---- search ----

let session = mb.newSession()
let searchAbort = null
let searchTimer = 0

function wireSearch() {
  ui.search.addEventListener('input', () => {
    clearTimeout(searchTimer)
    const q = ui.search.value.trim()
    if (q.length < 3) { ui.results.hidden = true; return }
    // Wait for a pause in typing so each search costs one request, not one per letter.
    searchTimer = setTimeout(() => runSearch(q), 350)
  })
  ui.search.addEventListener('focus', () => { if (ui.results.childElementCount) ui.results.hidden = false })
  ui.search.addEventListener('keydown', e => { if (e.key === 'Escape') { ui.search.blur(); ui.results.hidden = true } })
}

async function runSearch(q) {
  searchAbort?.abort()
  searchAbort = new AbortController()
  try {
    const list = await mb.suggest(q, here || mapCenter(), session, searchAbort.signal)
    ui.results.replaceChildren(...(list.length ? list.map(s => el('button', { class: 'result', onclick: () => pick(s) },
      el('span', { class: 'ic pin', html: '<svg viewBox="0 0 24 24"><path d="M12 2.5c-4.1 0-7.5 3.2-7.5 7.3 0 5.5 7.5 12.7 7.5 12.7s7.5-7.2 7.5-12.7c0-4.1-3.4-7.3-7.5-7.3zm0 10.3a3 3 0 1 1 0-6 3 3 0 0 1 0 6z" fill="currentColor"/></svg>' }),
      el('span', { class: 'rtext' }, el('strong', {}, s.name), el('span', { class: 'muted' }, s.detail)),
      s.distance != null ? el('span', { class: 'dist muted' }, fmtDistance(s.distance, settings.metric)) : null))
      : [el('div', { class: 'empty muted' }, 'Nothing found')]))
    ui.results.hidden = false
  } catch (e) {
    if (e.name !== 'AbortError') toast('Search failed. Check the connection.')
  }
}

function mapCenter() {
  const c = map.getCenter()
  return [c.lng, c.lat]
}

async function pick(s) {
  ui.results.hidden = true
  ui.search.blur()
  try {
    const place = await mb.retrieve(s.id, session)
    session = mb.newSession()
    ui.search.value = place.name
    preview(place)
  } catch {
    toast('Could not open that place.')
  }
}

// ---- route preview and driving ----

async function preview(place) {
  endTrip(false)
  trip = { place }
  if (!destMarker) {
    const pin = el('div', { class: 'dest', html: '<svg viewBox="0 0 36 46"><path d="M18 1C8.6 1 1 8.4 1 17.6 1 30 18 45 18 45s17-15 17-27.4C35 8.4 27.4 1 18 1z" fill="#000" stroke="#fff" stroke-width="2"/><circle cx="18" cy="17" r="6" fill="#fff"/></svg>' })
    destMarker = new mapboxgl.Marker({ element: pin, anchor: 'bottom' })
  }
  destMarker.setLngLat(place.lnglat).addTo(map)
  follow = false
  ui.recenter.hidden = !here
  showCard(place, null)
  if (!here) {
    map.flyTo({ center: place.lnglat, zoom: 15, pitch: 0, essential: true })
    return
  }
  try {
    const route = await mb.route(here, place.lnglat)
    if (trip?.place !== place) return
    trip.route = route
    drawRoute(route.coords)
    fitRoute(route.coords)
    showCard(place, route)
  } catch (e) {
    showCard(place, null, e.message || 'No route found')
    map.flyTo({ center: place.lnglat, zoom: 15, essential: true })
  }
}

function showCard(place, route, error) {
  const go = el('button', { class: 'btn primary', disabled: !route, onclick: startDrive }, 'Drive')
  ui.card.replaceChildren(
    el('div', { class: 'card-head' },
      el('div', {}, el('h3', {}, place.name), el('p', { class: 'muted' }, place.detail)),
      el('button', { class: 'iconbtn', 'aria-label': 'Close', onclick: () => endTrip(true) }, icon('close'))),
    route
      ? el('p', { class: 'eta' }, el('strong', {}, fmtDuration(route.duration)), ' \u00b7 ', fmtDistance(route.distance, settings.metric), ' \u00b7 arrive ', fmtArrival(route.duration))
      : el('p', { class: 'eta muted' }, error || (here ? 'Finding a route\u2026' : 'Waiting for your location to plan a route')),
    el('div', { class: 'row' }, go))
  ui.card.hidden = false
}

function drawRoute(coords) {
  const src = map.getSource('route')
  if (src) src.setData({ type: 'Feature', geometry: { type: 'LineString', coordinates: coords }, properties: {} })
}

function fitRoute(coords) {
  const b = new mapboxgl.LngLatBounds(coords[0], coords[0])
  coords.forEach(c => b.extend(c))
  const w = map.getContainer().clientWidth
  const pad = { top: 120, bottom: 80, left: 460, right: 120 }
  if (videoSide === 'right') pad.right += w * 0.5
  if (videoSide === 'left') pad.left = w * 0.5 + 60
  map.fitBounds(b, { padding: pad, pitch: 0, bearing: 0, duration: 900, maxZoom: 16 })
}

function startDrive() {
  if (!trip?.route) return
  trip.tracker = new RouteTracker(trip.route)
  trip.driving = true
  trip.offCount = 0
  trip.lastReroute = Date.now()
  trip.spoken = {}
  ui.card.hidden = true
  ui.top.hidden = true
  ui.banner.hidden = false
  ui.tripBar.hidden = false
  follow = true
  ui.recenter.hidden = true
  driveStep()
  moveCamera(false)
}

function driveStep() {
  const t = trip
  if (!t?.tracker || !here) return
  const p = t.tracker.update(here)
  if (p.arrived) {
    speak('You have arrived at ' + t.place.name)
    toast('Arrived at ' + t.place.name)
    endTrip(true)
    return
  }
  drawRoute([here, ...p.ahead])
  renderBanner(p)
  renderTripBar(p)
  announce(p)
  // Off the line for a few fixes in a row: ask for a new route, at most every 20 seconds.
  t.offCount = p.off > 45 ? t.offCount + 1 : 0
  if (t.offCount >= 3 && Date.now() - t.lastReroute > 20000) reroute()
}

async function reroute() {
  const t = trip
  t.lastReroute = Date.now()
  t.offCount = 0
  try {
    const route = await mb.route(here, t.place.lnglat)
    if (trip !== t) return
    t.route = route
    t.tracker = new RouteTracker(route)
    t.spoken = {}
  } catch { /* keep the old line and try again later */ }
}

function renderBanner(p) {
  const s = p.next
  ui.banner.replaceChildren(
    el('div', { class: 'turn', html: maneuverIcon(s.type, s.modifier) }),
    el('div', { class: 'turn-text' },
      el('div', { class: 'turn-dist' }, fmtDistance(p.toNext, settings.metric)),
      el('div', { class: 'turn-road' }, s.road || s.text)))
}

function renderTripBar(p) {
  ui.tripBar.replaceChildren(
    el('div', { class: 'trip-stats' },
      el('strong', {}, fmtArrival(p.timeLeft)),
      el('span', { class: 'muted' }, fmtDuration(p.timeLeft) + ' \u00b7 ' + fmtDistance(p.remaining, settings.metric))),
    el('button', { class: 'btn danger', onclick: () => endTrip(true) }, 'End'))
}

function announce(p) {
  const t = trip, s = p.next, key = p.nextIndex
  if (!settings.voice || !s.text) return
  const far = speed > 20 ? 800 : 300
  const spokenDistance = fmtDistance(p.toNext, settings.metric).replace('ft', 'feet').replace('mi', 'miles').replace('km', 'kilometers').replace(/ m$/, ' meters')
  if (p.toNext < far && p.toNext > 120 && !t.spoken[key + 'f']) {
    t.spoken[key + 'f'] = true
    speak(s.type === 'arrive' ? 'Your destination is in ' + spokenDistance : 'In ' + spokenDistance + ', ' + lowerFirst(s.text))
  } else if (p.toNext <= 60 && s.type !== 'arrive' && !t.spoken[key + 'n']) {
    t.spoken[key + 'n'] = true
    speak(s.text)
  }
}

const lowerFirst = s => s.charAt(0).toLowerCase() + s.slice(1)

function speak(text) {
  if (!settings.voice || !window.speechSynthesis) return
  const u = new SpeechSynthesisUtterance(text)
  u.rate = 1.02
  speechSynthesis.cancel()
  speechSynthesis.speak(u)
}

function endTrip(clearSearch) {
  trip = null
  ui.banner.hidden = true
  ui.tripBar.hidden = true
  ui.card.hidden = true
  ui.top.hidden = false
  destMarker?.remove()
  if (map?.getSource('route')) drawRoute([])
  if (clearSearch) ui.search.value = ''
  if (map && here) {
    follow = true
    ui.recenter.hidden = true
    map.easeTo({ center: here, pitch: 0, bearing: 0, zoom: 15.5, padding: cameraPadding(false), duration: 800 })
  }
}

// ---- style menu ----

function toggleStyleMenu() {
  if (!ui.menu.hidden) { ui.menu.hidden = true; return }
  renderMenu()
  ui.menu.hidden = false
}

function renderMenu() {
  const save = () => { store.set('settings', settings); applyStyle(); renderMenu() }
  const toggle = (key, label) => el('label', { class: 'switch' },
    el('span', {}, label),
    el('input', { type: 'checkbox', checked: settings[key], onchange: e => { settings[key] = e.target.checked; save() } }))
  const lightName = l => l === 'auto' ? 'Auto (' + resolveLight('auto') + ')' : l[0].toUpperCase() + l.slice(1)
  ui.menu.replaceChildren(
    el('div', { class: 'menu-head' }, el('h3', {}, 'Map'), el('button', { class: 'iconbtn', 'aria-label': 'Close', onclick: () => { ui.menu.hidden = true } }, icon('close'))),
    el('div', { class: 'label muted' }, 'Look'),
    el('div', { class: 'looks' }, LOOKS.map(look => el('button', {
      class: 'look' + (settings.look === look.name ? ' on' : ''),
      onclick: () => { settings.look = look.name; save() },
    }, el('span', { class: 'swatch' }, look.swatch.map(c => el('i', { style: 'background:' + c }))), look.name))),
    el('div', { class: 'label muted' }, 'Light'),
    el('div', { class: 'seg' }, LIGHTS.map(l => el('button', {
      class: settings.light === l ? 'on' : '',
      onclick: () => { settings.light = l; save() },
    }, lightName(l)))),
    toggle('buildings3d', '3D buildings'),
    toggle('pois', 'Places of interest'),
    toggle('voice', 'Spoken directions'),
    toggle('metric', 'Kilometers'),
    el('button', { class: 'btn ghost small signout', onclick: () => { store.set('token', null); location.reload() } }, 'Sign out of Mapbox'))
}
