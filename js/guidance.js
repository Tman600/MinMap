// Follows the car along a route: where it is on the line, the next turn, and what's left.

const R = 6371008.8
const rad = d => d * Math.PI / 180

export function distance(a, b) {
  const dLat = rad(b[1] - a[1]), dLng = rad(b[0] - a[0])
  const h = Math.sin(dLat / 2) ** 2 + Math.cos(rad(a[1])) * Math.cos(rad(b[1])) * Math.sin(dLng / 2) ** 2
  return 2 * R * Math.asin(Math.sqrt(h))
}

export function bearing(a, b) {
  const y = Math.sin(rad(b[0] - a[0])) * Math.cos(rad(b[1]))
  const x = Math.cos(rad(a[1])) * Math.sin(rad(b[1])) - Math.sin(rad(a[1])) * Math.cos(rad(b[1])) * Math.cos(rad(b[0] - a[0]))
  return (Math.atan2(y, x) * 180 / Math.PI + 360) % 360
}

// Nearest point on segment a-b to p, in a local flat projection (fine at street scale).
function project(p, a, b) {
  const k = Math.cos(rad(p[1])) * R * Math.PI / 180, m = R * Math.PI / 180
  const ax = (a[0] - p[0]) * k, ay = (a[1] - p[1]) * m
  const bx = (b[0] - p[0]) * k, by = (b[1] - p[1]) * m
  const dx = bx - ax, dy = by - ay
  const len2 = dx * dx + dy * dy
  const t = len2 ? Math.max(0, Math.min(1, -(ax * dx + ay * dy) / len2)) : 0
  const x = ax + t * dx, y = ay + t * dy
  return { t, off: Math.hypot(x, y) }
}

export class RouteTracker {
  constructor(route) {
    this.route = route
    const c = route.coords
    this.cum = [0]
    for (let i = 1; i < c.length; i++) this.cum.push(this.cum[i - 1] + distance(c[i - 1], c[i]))
    this.total = this.cum[this.cum.length - 1] || 1
    // Where each maneuver sits along the line, searched forwards so loops don't confuse it.
    let from = 0
    this.stepAt = route.steps.map(s => {
      let best = from, bestD = Infinity
      for (let i = from; i < c.length; i++) {
        const d = distance(c[i], s.at)
        if (d < bestD) { bestD = d; best = i }
        if (bestD < 2 && d > 200) break
      }
      from = best
      return this.cum[best]
    })
    this.seg = 0
    this.along = 0
  }

  // Returns progress for a GPS fix: off-route distance, next maneuver, distance and time left.
  update(pos) {
    const c = this.route.coords
    let best = this.seg, bestOff = Infinity, bestT = 0
    const lo = Math.max(0, this.seg - 3), hi = Math.min(c.length - 1, this.seg + 120)
    for (let i = lo; i < hi; i++) {
      const pr = project(pos, c[i], c[i + 1])
      if (pr.off < bestOff) { bestOff = pr.off; best = i; bestT = pr.t }
    }
    if (bestOff > 80) {
      // Lost the window (tunnel, GPS jump): search the whole line once.
      for (let i = 0; i < c.length - 1; i++) {
        const pr = project(pos, c[i], c[i + 1])
        if (pr.off < bestOff) { bestOff = pr.off; best = i; bestT = pr.t }
      }
    }
    this.seg = best
    const segLen = this.cum[best + 1] - this.cum[best]
    this.along = this.cum[best] + segLen * bestT

    let next = this.stepAt.findIndex((at, i) => i > 0 && at > this.along + 5)
    if (next < 0) next = this.stepAt.length - 1
    const remaining = Math.max(0, this.total - this.along)
    return {
      off: bestOff,
      along: this.along,
      nextIndex: next,
      next: this.route.steps[next],
      toNext: Math.max(0, this.stepAt[next] - this.along),
      remaining,
      timeLeft: this.route.duration * (remaining / this.total),
      arrived: remaining < 25,
      ahead: this.route.coords.slice(best + 1),
    }
  }
}

// A turn arrow for the guidance banner.
export function maneuverIcon(type, modifier) {
  if (type === 'arrive') {
    return '<svg viewBox="0 0 48 48"><path d="M24 6c-7 0-12.5 5.4-12.5 12.2C11.5 27.5 24 40 24 40s12.5-12.5 12.5-21.8C36.5 11.4 31 6 24 6zm0 17a5 5 0 1 1 0-10 5 5 0 0 1 0 10z" fill="currentColor"/></svg>'
  }
  const angles = { uturn: 180, 'sharp right': 135, right: 90, 'slight right': 40, straight: 0, 'slight left': -40, left: -90, 'sharp left': -135 }
  let a = angles[modifier] ?? 0
  if (type === 'roundabout' || type === 'rotary') a = angles[modifier] ?? 90
  if (modifier === 'uturn') {
    return '<svg viewBox="0 0 48 48"><path d="M30 42V18a8 8 0 0 0-16 0v12" fill="none" stroke="currentColor" stroke-width="5" stroke-linecap="round"/><path d="M7 26l7 9 7-9" fill="none" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/></svg>'
  }
  // Stem from the bottom, then bend toward the turn angle.
  const r = rad(a), len = 14
  const ex = 24 + Math.sin(r) * len, ey = 22 - Math.cos(r) * len
  const hx = Math.sin(r), hy = -Math.cos(r)
  const px = -hy, py = hx
  const tip = [ex + hx * 4, ey + hy * 4]
  const l = [ex - hx * 3 + px * 6, ey - hy * 3 + py * 6]
  const rr = [ex - hx * 3 - px * 6, ey - hy * 3 - py * 6]
  const f = n => n.toFixed(1)
  return `<svg viewBox="0 0 48 48"><path d="M24 44V22L${f(ex)} ${f(ey)}" fill="none" stroke="currentColor" stroke-width="5" stroke-linecap="round" stroke-linejoin="round"/>` +
    `<path d="M${f(tip[0])} ${f(tip[1])}L${f(l[0])} ${f(l[1])}L${f(rr[0])} ${f(rr[1])}z" fill="currentColor" stroke="currentColor" stroke-width="2" stroke-linejoin="round"/></svg>`
}
