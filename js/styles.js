// Map looks, ported from the Android app's PRESETS (MapStyle.kt). Each is Mapbox Standard config,
// so setConfig() on the "basemap" import applies it; keys left out fall back to Standard's defaults.

export const LOOKS = [
  { name: 'Standard', config: {} },
  { name: 'Monochrome', config: { theme: 'monochrome' }, swatch: ['#EDEDED', '#C4C4C4', '#DADADA', '#FFFFFF', '#BDBDBD'] },
  {
    name: 'Silver',
    config: {
      colorLand: '#F5F5F5', colorBuildings: '#EEEEEE', colorWater: '#C9C9C9', colorGreenspace: '#E5E5E5',
      colorRoads: '#FFFFFF', colorTrunks: '#FFFFFF', colorMotorways: '#DADADA',
      colorPlaceLabels: '#616161', colorRoadLabels: '#616161', showLandmarkIcons: false,
    },
  },
  {
    name: 'Retro',
    config: {
      colorLand: '#DFD2AE', colorBuildings: '#E8DCC0', colorWater: '#B9D3C2', colorGreenspace: '#A5B076',
      colorRoads: '#F5F1E6', colorTrunks: '#FDFCF8', colorMotorways: '#F8C967', colorAdminBoundaries: '#C9B2A6',
      colorPlaceLabels: '#523735', colorRoadLabels: '#523735',
    },
  },
  {
    name: 'Aubergine',
    dark: true,
    config: {
      colorLand: '#1D2C4D', colorBuildings: '#283D6A', colorWater: '#0E1626', colorGreenspace: '#023E58',
      colorRoads: '#304A7D', colorTrunks: '#3A5A94', colorMotorways: '#2C6675', colorAdminBoundaries: '#4B6878',
      colorPlaceLabels: '#8EC3B9', colorRoadLabels: '#8EC3B9',
    },
  },
  {
    name: 'Minimal',
    config: {
      colorLand: '#FFFFFF', colorBuildings: '#F2F2F2', colorWater: '#DCE6EE', colorGreenspace: '#EEF3EA',
      colorRoads: '#EBEBEB', colorMotorways: '#D6D6D6', colorPlaceLabels: '#8A8A8A', colorRoadLabels: '#8A8A8A',
      showPointOfInterestLabels: false, showTransitLabels: false, showLandmarkIcons: false, show3dTrees: false,
      theme: 'faded',
    },
  },
]

for (const look of LOOKS) {
  if (!look.swatch) {
    const c = look.config
    look.swatch = c.colorLand ? [c.colorLand, c.colorWater, c.colorGreenspace, c.colorRoads, c.colorMotorways] : ['#F2EFE9', '#9CC0F9', '#C8E6C0', '#FFFFFF', '#FFD27A']
  }
}

export const LIGHTS = ['auto', 'dawn', 'day', 'dusk', 'night']

// "Auto" follows the clock; good enough without a sunrise table.
export function resolveLight(light, date = new Date()) {
  if (light !== 'auto') return light
  const h = date.getHours() + date.getMinutes() / 60
  if (h >= 6 && h < 7.5) return 'dawn'
  if (h >= 7.5 && h < 18) return 'day'
  if (h >= 18 && h < 20) return 'dusk'
  return 'night'
}

export function buildConfig(settings) {
  const look = LOOKS.find(l => l.name === settings.look) || LOOKS[0]
  const config = { ...look.config, lightPreset: resolveLight(settings.light) }
  if (!settings.buildings3d) config.show3dBuildings = false
  if (!settings.pois) config.showPointOfInterestLabels = false
  return config
}

export function isDark(settings) {
  const look = LOOKS.find(l => l.name === settings.look)
  const light = resolveLight(settings.light)
  return !!look?.dark || light === 'night' || light === 'dusk'
}
