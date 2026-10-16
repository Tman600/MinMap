// One page, several roles: the car shows the map, a phone that opens #cast sends video to it,
// #watch is the car's video panel on its own, and #check tests the connection pieces.
if (location.hash.startsWith('#cast')) {
  import('./phone.js').then(m => m.start())
} else if (location.hash.startsWith('#give')) {
  import('./give.js').then(m => m.start())
} else if (location.hash.startsWith('#check')) {
  import('./check.js').then(m => m.start())
} else if (location.hash.startsWith('#watch')) {
  import('./watch.js').then(m => m.watchOnly())
} else {
  import('./car.js').then(m => m.start())
}
