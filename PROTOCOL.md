# Phone-to-car pairing protocol (v2)

Shared by the car page (`js/link.js` Receiver), the phone web page (`js/link.js` Sender) and the
MinMap Mirror Android app (`MinMapMirror/.../link`). Change all three together.

## Pairing code

8 characters from `23456789ABCDEFGHJKMNPQRSTUVWXYZ`, shown as `ABCD-EFGH`. The car keeps its
code in localStorage, so a phone can reconnect later. The QR code holds `<site>#cast=<CODE>`.

From the code:
- topic = hex(SHA-256("minmap-cast-v2|topic|" + code))
- key = SHA-256("minmap-cast-v2|key|" + code), the raw 32 bytes, used as an AES-256-GCM key

## Notes over Nostr

Each side makes a throwaway Nostr key pair per session, then:
- connects to the relays in `RELAYS`;
- subscribes with `["REQ","mm",{"kinds":[25050],"#x":[topic],"since":now-30}]`.

The event fields:
- kind 25050, which is ephemeral: relays forward it and don't store it;
- tags `[["x", topic]]`;
- content = base64(iv[12] || AES-GCM ciphertext+tag) of a JSON message.

The id is SHA-256 of the NIP-01 serialization `[0,pubkey,created_at,kind,tags,content]` with no
whitespace and `/` not escaped. sig is BIP340 Schnorr. Receivers drop:
- their own events;
- duplicates by id;
- anything that fails to decrypt.

## Messages

- `{"t":"offer","id":<offer id>,"from":<phone id>,"sdp":<offer SDP>,"ice":[...]?}`. `ice` is
  optional. When the phone has a TURN relay set up (the owner's Cloudflare key, kept on the phone),
  it holds short-lived `{urls, username, credential}` entries. The car adds them to its own ICE
  servers, so both ends can meet through the relay when the network blocks a direct link. The
  phone sends this
  every 3 s until answered, then every 12 s after 2 minutes.
- `{"t":"answer","id":<offer id>,"to":<phone id>,"sdp":<answer SDP>}`. The car resends the same
  answer if the same offer id arrives again.
- `{"t":"bye","from":<phone id>}`. The phone page is closing.

ICE is not trickled. Each side waits for gathering to complete (at most 3 s) and sends the
complete SDP. STUN: stun.l.google.com:19302 and stun.cloudflare.com:3478.

The phone is always the offerer:
- it sends video and audio (sendonly);
- it opens a data channel named `ctl`;
- it starts a new offer if the connection fails, or stays disconnected for 4 s.

The car keeps one phone at a time; a new offer replaces the old connection. The car's answer
asks for stereo Opus (`stereo=1;sprop-stereo=1;maxaveragebitrate=192000`).

## Data channel `ctl`

- Phone to car, about every 1 to 2 s:
  `{"t":"st","title":..,"playing":bool,"time":s,"dur":s,"screen":bool}`. With `screen: true`,
  the car hides play and seek.
- Car to phone: `{"op":"toggle"}`, `{"op":"seek","to":s}` or `{"op":"skip","by":s}`. Only a
  video file can obey these; a mirrored screen ignores them.
