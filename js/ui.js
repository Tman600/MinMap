// Small DOM and storage helpers shared by the car and phone pages.

export function el(tag, attrs = {}, ...kids) {
  const node = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) {
    if (v == null || v === false) continue
    if (k === 'class') node.className = v
    else if (k === 'html') node.innerHTML = v
    else if (k.startsWith('on')) node.addEventListener(k.slice(2), v)
    else node.setAttribute(k, v === true ? '' : v)
  }
  for (const kid of kids.flat()) {
    if (kid == null || kid === false) continue
    node.append(kid instanceof Node ? kid : document.createTextNode(String(kid)))
  }
  return node
}

// Browser storage can throw (private windows, blocked site data); MinMap works without it.
export const store = {
  get(key, fallback = null) {
    try {
      const v = localStorage.getItem('minmap.' + key)
      return v == null ? fallback : JSON.parse(v)
    } catch { return fallback }
  },
  set(key, value) {
    try {
      if (value == null) localStorage.removeItem('minmap.' + key)
      else localStorage.setItem('minmap.' + key, JSON.stringify(value))
    } catch { /* not saved */ }
  },
}

export function loadScript(src) {
  return new Promise((resolve, reject) => {
    const s = el('script', { src })
    s.onload = resolve
    s.onerror = () => reject(new Error('Could not load ' + src))
    document.head.append(s)
  })
}

export const icons = {
  play: '<svg viewBox="0 0 24 24"><path d="M8 5.5v13l10.5-6.5z" fill="currentColor"/></svg>',
  pause: '<svg viewBox="0 0 24 24"><path d="M7 5h3.5v14H7zM13.5 5H17v14h-3.5z" fill="currentColor"/></svg>',
  back10: '<svg viewBox="0 0 24 24"><path d="M12 5V2L7 6l5 4V7a6 6 0 1 1-6 6H4a8 8 0 1 0 8-8z" fill="currentColor"/><text x="12" y="16.5" font-size="6.5" text-anchor="middle" fill="currentColor" font-family="system-ui" font-weight="700">10</text></svg>',
  fwd10: '<svg viewBox="0 0 24 24"><path d="M12 5V2l5 4-5 4V7a6 6 0 1 0 6 6h2a8 8 0 1 1-8-8z" fill="currentColor"/><text x="12" y="16.5" font-size="6.5" text-anchor="middle" fill="currentColor" font-family="system-ui" font-weight="700">10</text></svg>',
  close: '<svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18" stroke="currentColor" stroke-width="2.2" stroke-linecap="round"/></svg>',
  layers: '<svg viewBox="0 0 24 24"><path d="M12 3l9 5-9 5-9-5z" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/><path d="M3 12.5l9 5 9-5M3 16.5l9 5 9-5" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linejoin="round"/></svg>',
  locate: '<svg viewBox="0 0 24 24"><path d="M20.5 3.5L3.5 10.6l7.3 2.1 2.1 7.3z" fill="currentColor"/></svg>',
  screen: '<svg viewBox="0 0 24 24"><rect x="2.5" y="4" width="19" height="13" rx="2" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M10 8.5v5l4.5-2.5z" fill="currentColor"/><path d="M8 20.5h8" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"/></svg>',
  search: '<svg viewBox="0 0 24 24"><circle cx="10.5" cy="10.5" r="6" fill="none" stroke="currentColor" stroke-width="2"/><path d="M15 15l5 5" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>',
  pin: '<svg viewBox="0 0 24 24"><path d="M12 2.5c-4.1 0-7.5 3.2-7.5 7.3 0 5.5 7.5 12.7 7.5 12.7s7.5-7.2 7.5-12.7c0-4.1-3.4-7.3-7.5-7.3zm0 10.3a3 3 0 1 1 0-6 3 3 0 0 1 0 6z" fill="currentColor"/></svg>',
  side: '<svg viewBox="0 0 24 24"><rect x="3" y="5" width="18" height="14" rx="2" fill="none" stroke="currentColor" stroke-width="1.8"/><path d="M8 12h8M13 9l3 3-3 3" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  expand: '<svg viewBox="0 0 24 24"><path d="M4 9V4h5M20 9V4h-5M4 15v5h5M20 15v5h-5" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg>',
  shrink: '<svg viewBox="0 0 24 24"><path d="M9 4v5H4M15 4v5h5M9 20v-5H4M15 20v-5h5" fill="none" stroke="currentColor" stroke-width="1.9" stroke-linecap="round" stroke-linejoin="round"/></svg>',
}

export function icon(name) {
  return el('span', { class: 'ic', html: icons[name] })
}

// The passenger-only notice. Resolves true only when the person ticks the box and accepts.
export function consent() {
  return new Promise(resolve => {
    const accept = el('button', { class: 'btn primary', disabled: true }, 'Accept')
    const box = el('input', { type: 'checkbox', id: 'consent-box' })
    box.addEventListener('change', () => { accept.disabled = !box.checked })
    const done = ok => { scrim.remove(); resolve(ok) }
    accept.addEventListener('click', () => done(true))
    const scrim = el('div', { class: 'scrim' },
      el('div', { class: 'sheet consent', role: 'dialog', 'aria-modal': 'true', 'aria-labelledby': 'consent-title' },
        el('h2', { id: 'consent-title' }, 'Passenger use only'),
        el('p', {}, 'Video is for passengers. The driver must not watch it while the car is moving.'),
        el('p', {}, 'Recommended only while Autopilot or FSD (Supervised) is engaged. Both still need the driver\u2019s full attention on the road, ready to take over at any moment.'),
        el('p', { class: 'fine' }, 'Video on a screen the driver can see is against the law in many places. You are responsible for following the law where you drive and for how this is used.'),
        el('label', { class: 'check', for: 'consent-box' }, box, el('span', {}, 'I understand and will use this for passengers only.')),
        el('div', { class: 'row end' },
          el('button', { class: 'btn', onclick: () => done(false) }, 'Cancel'),
          accept),
      ))
    document.body.append(scrim)
  })
}

export function toast(text, ms = 3200) {
  const t = el('div', { class: 'toast' }, text)
  document.body.append(t)
  setTimeout(() => t.classList.add('out'), ms)
  setTimeout(() => t.remove(), ms + 400)
}

export function fmtClock(sec) {
  if (!isFinite(sec) || sec < 0) sec = 0
  sec = Math.floor(sec)
  const h = Math.floor(sec / 3600), m = Math.floor(sec / 60) % 60, s = sec % 60
  const mm = h ? String(m).padStart(2, '0') : String(m)
  return (h ? h + ':' : '') + mm + ':' + String(s).padStart(2, '0')
}

export function fmtDuration(sec) {
  const min = Math.max(1, Math.round(sec / 60))
  if (min < 60) return min + ' min'
  const h = Math.floor(min / 60), m = min % 60
  return m ? `${h} h ${m} min` : `${h} h`
}

export function fmtDistance(m, metric) {
  if (metric) {
    if (m < 950) return Math.max(10, Math.round(m / 10) * 10) + ' m'
    return (m / 1000).toFixed(m < 9500 ? 1 : 0) + ' km'
  }
  const ft = m * 3.28084
  if (ft < 1000) return Math.max(50, Math.round(ft / 50) * 50) + ' ft'
  const mi = m / 1609.344
  return (mi < 9.5 ? mi.toFixed(1) : Math.round(mi)) + ' mi'
}

export function fmtArrival(secFromNow) {
  const d = new Date(Date.now() + secFromNow * 1000)
  return d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
}
