// Mapbox web APIs, using the token the person pasted in (same model as the Android sign-in screen).
import { store } from './ui.js'

const API = 'https://api.mapbox.com'
const token = () => store.get('token', '')

export class MapboxError extends Error {
  constructor(status, message) { super(message); this.status = status }
}

async function getJson(url) {
  const res = await fetch(url)
  if (!res.ok) {
    let msg = res.statusText
    try { msg = (await res.json()).message || msg } catch { }
    throw new MapboxError(res.status, msg)
  }
  return res.json()
}

// Null when the token is fine, otherwise a reason to show.
export async function checkToken(t) {
  if (!/^pk\./.test(t)) return 'Use a public token. It starts with pk.'
  try {
    const j = await getJson(`${API}/tokens/v2?access_token=${encodeURIComponent(t)}`)
    if (j.code === 'TokenValid') return null
    return 'Mapbox did not accept that token (' + (j.code || 'unknown') + ').'
  } catch (e) {
    return e.status ? 'Mapbox did not accept that token (' + e.status + ').' : 'Could not reach Mapbox. Check the connection.'
  }
}

export function newSession() {
  return crypto.randomUUID ? crypto.randomUUID() : String(Math.random()).slice(2)
}

export async function suggest(q, near, session, signal) {
  const p = new URLSearchParams({ q, limit: '6', language: 'en', session_token: session, access_token: token() })
  if (near) p.set('proximity', near[0].toFixed(5) + ',' + near[1].toFixed(5))
  const res = await fetch(`${API}/search/searchbox/v1/suggest?${p}`, { signal })
  if (!res.ok) throw new MapboxError(res.status, 'Search failed')
  const j = await res.json()
  return (j.suggestions || []).map(s => ({
    id: s.mapbox_id,
    name: s.name,
    detail: s.place_formatted || s.full_address || '',
    distance: s.distance,
  }))
}

export async function retrieve(id, session) {
  const p = new URLSearchParams({ session_token: session, access_token: token() })
  const j = await getJson(`${API}/search/searchbox/v1/retrieve/${encodeURIComponent(id)}?${p}`)
  const f = j.features?.[0]
  if (!f) throw new MapboxError(404, 'Place not found')
  return {
    name: f.properties.name,
    detail: f.properties.full_address || f.properties.place_formatted || '',
    lnglat: f.geometry.coordinates,
  }
}

// One driving-traffic route with its turn steps.
export async function route(from, to) {
  const coords = `${from[0]},${from[1]};${to[0]},${to[1]}`
  const p = new URLSearchParams({
    geometries: 'geojson', overview: 'full', steps: 'true', alternatives: 'false', access_token: token(),
  })
  const j = await getJson(`${API}/directions/v5/mapbox/driving-traffic/${coords}?${p}`)
  const r = j.routes?.[0]
  if (!r) throw new MapboxError(404, j.message || 'No route found')
  const steps = r.legs.flatMap(leg => leg.steps).map(s => ({
    at: s.maneuver.location,
    type: s.maneuver.type,
    modifier: s.maneuver.modifier || '',
    text: s.maneuver.instruction || '',
    road: s.name || s.ref || '',
    distance: s.distance,
  }))
  return { coords: r.geometry.coordinates, duration: r.duration, distance: r.distance, steps }
}
