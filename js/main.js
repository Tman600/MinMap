// One page, several roles: the car shows the map, a phone that opens #cast sends video to it,
// #watch is the car's video panel on its own, and #check tests the connection pieces.
if (location.hash.startsWith('#cast')) {
  import('./phone.js?v=21').then(m => m.start())
} else if (location.hash.startsWith('#give')) {
  import('./give.js?v=21').then(m => m.start())
} else if (location.hash.startsWith('#check')) {
  import('./check.js?v=21').then(m => m.start())
} else if (location.hash.startsWith('#watch')) {
  import('./watch.js?v=21').then(m => m.watchOnly())
} else {
  import('./car.js?v=21').then(m => m.start())
}
