# line_chrome

A pure-Python LINE client. It speaks the same Apache Thrift protocol as LINE's
desktop/Chrome clients, so it gets a real multi-device session — including
end-to-end encryption (letter sealing) for both sending and receiving.

```python
from line_chrome import LINE

line = LINE("you@example.com", "password")     # PIN confirmation on first run
line.send_message("ue8790ab5...", "こんにちは")  # sealed automatically if needed

@line.on_message
def on_msg(msg, op):
    print(f"{msg.sender}: {msg.text}")         # already decrypted

line.listen()
```

## Status

| Area | State |
|---|---|
| Email + password login, device cert, session resume | working |
| Send / receive plain text | working |
| E2EE 1:1 — encrypt **and** decrypt | working |
| E2EE group — decrypt | working |
| E2EE group — encrypt | **not implemented** (raises `NotImplementedError`) |
| Long-polling (`listen`) | implemented, lightly tested |
| Send media (image / video / audio / file) — unsealed chats | working |
| Send media — sealed chats | **not implemented** — needs the encrypted-media flow |
| Download media, including from sealed chats | working |
| Upload of large files | untested — likely needs chunked range upload |

## Install

```bash
pip install -r requirements.txt
```

Needs `httpx[http2]`, `requests`, `rsa`, `pycryptodome`, `cryptography`, `xxhash`.

## Logging in

```python
line = LINE("you@example.com", "password")   # first run: PIN on your phone
line = LINE(auth_token="...")                # subsequent runs
```

Tokens rotate on their own via the `x-line-next-access` response header;
`line.refresh_token()` forces it using the stored refresh token.

The first login prints a pincode (default `202202`) that you type into the LINE
app on your phone. That device confirmation is what hands over the E2EE key
chain, so it is not optional if you want sealed chats to work.

Afterwards three things are cached beside the package, **not** in the current
directory — so the script works no matter where it is launched from:

| Path | Contents |
|---|---|
| `.data/<email>.crt` | device cert; later logins skip the PIN |
| `.e2eeKeys/` | your E2EE private key (`key_<id>.json`, `<mid>.json`) |
| `.e2eePublicKeys/`, `.e2eeGroupKeys/` | cached peer and group keys |
| `.tokens/`, `.refreshToken/` | rotated access tokens |

Treat `.e2eeKeys/` as a secret: it can decrypt your messages. Losing it costs
another PIN confirmation, because LINE only ever hands the key out during a
device registration.

```python
LINE(email, password, save_path="/somewhere/else", pincode="123456")
```

## API

### Chats and contacts

```python
line.get_profile()                     # -> Profile
line.get_chats()                       # -> [Chat]   groups and rooms
line.get_chats(include_peers=True)     # ...plus one per contact
line.get_chat_mids(include_peers=True)  # -> [mid], no Chat lookup
line.get_all_contact_ids()             # -> [mid]
line.get_contacts(mids)                # -> [Contact]
line.get_contact(mid)                  # -> Contact, works for non-friends too
line.find_contact_by_userid("handle")  # -> Contact, by @LINE ID (unverified)
line.get_chat_summaries()              # -> [ChatSummary]  the "Chats" tab
```

`getAllChatMids` only returns groups and rooms. A 1:1 chat has no room object
server-side — it is addressed by the peer's own MID — so direct chats come from
the contact list instead. `get_chat_summaries()` merges both and attaches each
chat's last message, newest first:

```python
for s in line.get_chat_summaries():
    print(s.name, s.preview, s.timestamp)
```

There is no LINE call that returns this directly; the official client keeps a
local message box synced from `fetchOps`. This issues one
`getRecentMessagesV2(count=1)` per chat, concurrently — fine for tens of chats,
noticeable for hundreds.

### Messages

```python
line.send_message(to, "hello")                     # seals if the chat requires it
line.send_message(to, "hi", reply_to=message_id)
line.send_message(to, "hi", e2ee=True)             # force sealing
line.get_recent_messages(chat_mid, count=20)       # -> [Message], decrypted
line.unsend_message(message_id)
line.send_read_receipt(chat_mid, message_id)
```

MID prefixes: `u` user, `c` group, `r` room.

`Contact` and `Profile` expose `.picture_url()` / `.picture_url(preview=True)`,
since `picture_path` comes back CDN-relative and the API never sends the host.
`Contact.name` picks your nickname for someone over their own display name.

### Media

```python
line.send_image(to, "icon.png")            # path, URL or bytes
line.send_video(to, "clip.mp4", duration=15000)   # duration in ms
line.send_file(to, "doc.pdf")
line.send_media(to, data, "audio")         # image/video/audio/file/gif

line.download_image(msg)                   # -> bytes, decrypted if sealed
line.download_image(msg, path="out.jpg")
line.download_image(msg, size="preview")   # or "m800x1200", "w800"
```

There is no "attach a file to a message" call. The recipient travels in the
upload params as `tomid`, so **the upload itself creates the message** — the
send functions return an object id and hash rather than a `Message`.

Uploads go to the **gateway** (`gwz.line.naver.jp/oa/r/talk/m/reqseq`), not to
`obs.line-apps.com` as the config domain suggests. Any device type other than
`CHROMEOS` must trade its auth token via `acquireEncryptedAccessToken` and use
the part after the `\x1e` separator as `X-Line-Access`; the plain token is
rejected.

Downloads are keyed differently depending on sealing, which is easy to get
wrong:

| | URL | extra header |
|---|---|---|
| plain | `/oa/r/talk/m/<messageId>` | — |
| sealed | `/oa/r/talk/<SID>/<OID>` | `X-Talk-Meta` |

For sealed media the object is **not** under `talk/m` and is not keyed by the
message id at all — the sender records the real namespace and object id in
`contentMetadata` as `SID` (`emi`/`emv`/`ema`/`emf`) and `OID`. The fetch also
needs an `X-Talk-Meta` header: base64(json) wrapping a base64 TBinary struct
holding the message id at field 4.

The object itself is then decrypted with key material carried in the message:
HKDF-SHA256 (`info=b"FileEncryption"`) → encKey/macKey/nonce, AES-CTR, with a
trailing 32-byte HMAC to strip.

Sending to a sealed chat is not implemented — LINE rejects plain uploads there
exactly as it rejects plain text.

`send_message` sends plain first and, if the server answers
`E2EE_RETRY_ENCRYPT` (82), transparently re-sends encrypted. That mirrors the
official clients: whether a chat is sealed is the *recipient's* setting and is
not knowable up front.

### Receiving

```python
@line.on_message
def handler(msg, op): ...    # RECEIVE_MESSAGE only, decrypted

@line.on_op
def any_event(op): ...       # every operation

line.listen()                # blocks; Ctrl-C to stop
```

`listen()` seeds from `line.get_last_op_revision()` so it starts at the present rather
than replaying the account's history, then long-polls `fetchOps` on `/P4`.
Handlers run on daemon threads by default; pass `threaded=False` for
sequential dispatch. A handler that raises is logged, not fatal.

### E2EE internals

```python
line.e2ee_key                       # our key: {keyId, privKey, pubKey, e2eeVersion}
line.get_e2ee_public_keys()         # every key registered on the account
line.negotiate_e2ee_public_key(mid) # a peer's current key
line.decrypt_message(msg)           # usually unnecessary; done automatically
```

## How it works

Requests carry `X-Line-Application: DESKTOPWIN\t8.6.0.3277\tWINDOWS\t...`, so
LINE treats the client as a desktop app — which, unlike a phone, is allowed to
hold a concurrent session.

- **Auth** — `/api/v3p/rs` over the legy encryption proxy (`gf.line.naver.jp/enc`,
  AES-CBC + RSA-OAEP key wrap), TBinary.
- **Talk** — `/S4`, TCompact, plain HTTPS to `gwz.line.naver.jp`.
- **Poll** — `/P4`, TCompact. (`/P5` is the TMoreCompact variant and needs a
  decoder this library does not have.)
- **Objects** — `/oa/r/talk/m/reqseq` on the same gateway, raw bytes with a
  base64 `X-Obs-Params` header.
- **Login** — RSA-encrypted credentials → `loginV2` → PIN → `/LF1` long-poll →
  key chain → `confirmE2EELogin` → `loginV2` → v3 token.

### Letter sealing

1:1 uses ECDH between your key and the peer's, per message:

```
shared  = X25519(our_private, peer_public)
gcmKey  = SHA256(shared ‖ salt ‖ "Key")
aad     = to ‖ from ‖ senderKeyId ‖ recvKeyId ‖ specVersion ‖ contentType
chunks  = [salt, AES-256-GCM(gcmKey, nonce, json, aad), nonce, sKid, rKid]
```

Groups do not do this pairwise. The creator generates one key pair for the
group and gives each member a copy of the private half, wrapped with
`ECDH(creator, member)` — note **no salt** in that wrapping, unlike message
keys. Messages then use `ECDH(group_private, sender_public)`.

Which key id is *yours* depends on direction: for an incoming message you are
the receiver, but for one you sent, your key is the **sender** key id. Getting
this wrong makes your own messages undecryptable while everyone else's work.

## Known issues and limits

- **Messages sent by this client may show "This message can't be decrypted" on
  your other devices.** We sign with the key id lifted from the key chain
  rather than registering our own via `registerE2EEPublicKey`, so a device that
  never received that private half cannot read them. Run
  `python example.py keys` to see the account's keys. Not fully diagnosed.
- **Key rotation is one-way.** `negotiateE2EEPublicKey` returns only a peer's
  *current* key, so messages predating a rotation stay unreadable. Keys are
  cached to limit future loss; they cannot be recovered retroactively.
- **`getMessageBoxCompactWrapUpListV2`** would build the chat list in one call
  instead of N, but it is deprecated and neither reference implementation ships
  its response struct.
- **Device fingerprint is inconsistent** — a `DESKTOPWIN` application string
  alongside a Chrome user-agent and `X-Line-Chrome-Version`. Tolerated by the
  server so far.
- **Sending encrypted media is unimplemented.** It needs
  `determineMediaMessageFlow`, a dual upload (object plus `__ud-preview`
  subresource), and the key material sent as an E2EE dict payload. Receiving
  sealed media does work.
- **Large uploads are untested.** `genOBSParams` has handling for a `range` key
  (`bytes 0-N/N`), implying a chunked protocol this client does not implement.
  Small files upload fine; a large video may not.

## Gotchas worth knowing

Things that cost real debugging time here, in case you extend this:

- `unpack_compact` returns a struct result **unwrapped**, but keys a scalar or
  list result at `0`. Which access is correct depends on the method's declared
  Thrift return type. Several methods silently returned empty lists because of
  this.
- In TCompact, LIST is `0x09` and SET is `0x0A`. They are not the same type.
- Thrift BINARY fields must be sent as raw `bytes`. Base64-encoding the E2EE
  login secret makes the phone report "unknown error occurred".
- `errorCode: "SUCCESS"` is an outcome, not a failure.
- LINE sends plain text as `ContentType.NONE` (0), with the body in field 10.
- Sealed media is not stored under `talk/m` and is not addressed by message id.
  Read `SID`/`OID` out of `contentMetadata` instead — a download that 404s on
  sealed images but works on plain ones is this.

## Layout

```
line_chrome/
  client.py   LINE class: auth, talk, E2EE, polling
  thrift.py   TBinary / TCompact encode + decode
  types.py    Chat, Contact, Message, Operation, enums
example.py    CLI demo
```

## Credit

Protocol details derived from [CHRLINE](https://github.com/DeachSword/CHRLINE)

## Disclaimer

Unofficial, built by reverse-engineering. It violates LINE's terms of service
and can get your account restricted or banned. Use an account you can afford to
lose. No warranty.
