// One page, three roles: the car shows the map, a phone that opens #cast sends video to it,
// and #watch is the car's video panel on its own, without the map.
if (location.hash.startsWith('#cast')) {
  import('./phone.js').then(m => m.start())
} else if (location.hash.startsWith('#watch')) {
  import('./watch.js').then(m => m.watchOnly())
} else {
  import('./car.js').then(m => m.start())
}
