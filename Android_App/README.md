# LINE Chrome (Android)

A Kotlin/Compose Android client for LINE, built on a port of the
[`LINE_Chrome_Python`](LINE_Chrome_Python/) wrapper in this repo. It speaks
LINE's Apache Thrift protocol directly and signs in as a desktop client
(`DESKTOPWIN`), so it gets a real multi-device session alongside your phone —
including end-to-end encryption (letter sealing).

Dark theme only. `minSdk` 24, `targetSdk` 36, Material 3.

## Status

| Area | State |
|---|---|
| Email + password login, PIN confirmation, device cert | working |
| Session resume, token rotation, unattended re-login | working |
| Chat list (1:1 and group) with last message | working |
| Friend list, group list, search | working |
| Profile page (tap the conversation header) | working |
| Conversation view, send / receive text | working |
| Reply (long-press or double-tap) | working |
| Copy / share a message | working |
| Read receipts | manual only — never sent automatically |
| Stickers | rendered from the sticker CDN |
| Send images and video | working, unsealed chats only |
| Receive images and video, sealed **and** unsealed | working, with thumbnails |
| Receive audio and files | labelled `[Audio]` / `[File]`; not downloaded |
| E2EE 1:1 — encrypt **and** decrypt | working |
| E2EE group — decrypt | working |
| E2EE group — encrypt | **not implemented**, see [Known limits](#known-limits) |
| Background polling + notifications | working, see [Message delivery](#message-delivery) |
| Messages that arrived while the app was dead | delivered on next start |
| Foldable / tablet two-pane layout | working |
| Light theme | deliberately absent |

## Architecture

```
line/            protocol layer — a direct port of the Python client
  Thrift.kt        TBinary / TCompact encode + decode
  Crypto.kt        SHA-256, AES-ECB/CBC/CTR/GCM, HKDF, RSA, Curve25519
  XxHash32.kt      the 4-byte MAC legy wants on encrypted bodies
  Legy.kt          LINE's body-encryption proxy (auth traffic)
  ObsParams.kt     object-storage URLs and the X-Obs-Params JSON
  LineClient.kt    login, TalkService calls, polling, letter sealing, media
  LineTypes.kt     Message, Contact, Chat, Operation, enums
  E2eeStore.kt     on-disk key material, same layout as the Python client
data/            LineRepository (single source of UI state)
                 SessionStore (tokens) + CredentialStore (keystore-encrypted)
service/         PollingService (foreground) + Notifier
ui/              Compose screens, dark-only Material 3 theme
```

`LineRepository` is a process-wide singleton because the polling service and the
Activity have to share one message store, and a session can cost a PIN
confirmation to rebuild.

### Why BouncyCastle

Two things rule out the platform providers:

- `KeyAgreement.getInstance("XDH")` for Curve25519 only exists from API 33, and
  `minSdk` here is 24.
- LINE uses a **16-byte** AES-GCM nonce. Conscrypt accepts only 12 and throws
  otherwise, so message encryption goes through BouncyCastle's lightweight
  `GCMBlockCipher`.

## Logging in

Email and password, then a six-digit code shown in the app that you approve in
LINE on your phone. That confirmation is what hands over the E2EE key chain, so
it is not optional if you want sealed chats to work — and it only happens once,
because the device certificate is cached afterwards.

There is also an "advanced" field that accepts an existing access token (for
example one the Python client obtained). A token carries no E2EE key, so sealed
chats stay unreadable until you sign in with a password at least once.

Signing out deletes the stored key. Messages sealed with it become unreadable
and the next sign-in needs another PIN.

### Staying signed in

LINE expires the v3 access token every few days —
`[code=8] V3_TOKEN_CLIENT_LOGGED_OUT` — and the refresh token eventually goes
with it, so a client that only stores tokens keeps dropping out. With **Stay
signed in** ticked at login, recovery is automatic and happens in three steps:

1. renew with the refresh token, which is cheap and usually enough;
2. failing that, log in again with the stored email and password — normally
   **without a PIN**, because the device certificate from the first login is
   still on disk;
3. failing that, surface the login screen.

This runs in the background too, so a session that dies while the app is closed
is rebuilt and notifications keep arriving. **Settings → Sign-in** has a switch:
turning it off keeps the password but stops it being used, while *Forget saved
password* deletes it outright.

The password is encrypted with an AES key generated inside the **Android
keystore**, so the ciphertext in SharedPreferences is useless without a key that
cannot be extracted from the device. Signing out deletes it.

Recovery is serialised behind a mutex with a 30s grace window, and **failures
are cached alongside successes**: several loops tend to notice the same dead
session at once, and a wrong password that re-attempted `loginV2` on every call
would be a login storm — precisely the request volume that gets an account
flagged.

Only a real rejection reaches the login screen. A wrong password or a demand for
a PIN needs the user; a timeout does not, so a network failure leaves the UI
alone and hands off to a watchdog that retries in the background, backing off
from 15s to a 5-minute ceiling. Coming back from a tunnel therefore costs
nothing, while a genuinely wrong password stops being retried almost at once.

## Layout

Under 600dp wide the app is a single pane, and opening a conversation covers the
list. At or above it the list and the conversation sit side by side — a Fold's
inner screen is about 670dp and its cover screen about 340dp, so the two states
fall cleanly either side.

The test is on **width**, not on whether the device folds, so an ordinary phone
turned landscape gets the two-pane layout too.

The Chats/Friends/Settings bar belongs to the list rather than to the window, so
it stays under the list in both layouts — the whole left-hand side is one
`Scaffold` either way, just narrower when a conversation sits beside it.

Navigation state is held in `rememberSaveable` and keyed by MID alone, because
folding, unfolding and rotating all recreate the Activity. Losing the open
conversation on every fold would be miserable, and deriving the name and picture
from the MID means they stay current if a contact is renamed.

## Profiles

Tapping the name and picture at the top of a conversation opens a profile page:
avatar, display name, status message, a copyable MID, and a button to open the
chat. In a group, tapping a sender's avatar opens theirs, and the Friends tab
opens a profile rather than jumping straight into a conversation — it is a
directory, and looking someone up is as likely as wanting to message them.

Person profiles come from `getContactsV2` and refresh on open; cached details
render first so the page never appears empty. Groups have no contact record and
the protocol exposes **no roster call at all**, so a group page shows what the
chat list knows plus the people who have actually spoken in the messages loaded.
That list is labelled as such rather than passed off as the membership.

## Replies

Long-press a message for a menu — Reply, plus Copy and Share when there is text
— or double-tap to reply straight away. Both work on text, images, video and
stickers. A reply carries the original's id in field 21 with
`messageRelationType = REPLY`, which `ThriftTest` pins byte-for-byte against the
reference client.

Incoming replies render the quoted original inside the bubble. Only the recent
window of a conversation is held, so a reply reaching further back says the
original is not loaded rather than drawing an empty quote.

## Read receipts

Opening a chat clears its unread badge locally and tells LINE nothing. A read
receipt is visible to the other party, and glancing at a message is not the same
as wanting them to know you read it — so `sendChatChecked` only goes out when the
✓ button in the chat's top bar is pressed.

The ✓✓ button in the Chats tab clears **every** badge, also without telling LINE
anything.

## Media

### Sending

The photo and video buttons in the composer open the system picker — no storage
permission is involved, since the picker hands over just the one item chosen.
GIFs go up as `type=image` with `cat=original`, or LINE transcodes the animation
away; video carries its duration, read from `MediaMetadataRetriever`.

There is no "attach a file to a message" call. The recipient travels in the
upload parameters as `tomid`, so **the upload itself creates the message**.
Nothing is appended locally: the conversation is polled for up to 8s afterwards,
because the message is not in the message box the instant the upload returns.

Three things here are easy to get wrong, two of them inherited:

- the upload goes to the **gateway** (`gwz.line.naver.jp/oa/r/talk/m/reqseq`),
  not to `obs.line-apps.com` as the config domain suggests;
- any device type other than `CHROMEOS` must trade its auth token via
  `acquireEncryptedAccessToken` and use the part after the `0x1E` record
  separator — the plain token is rejected;
- `X-Obs-Params` is base64 of JSON, and Python's `json.dumps` defaults to `", "`
  separators **and** `ensure_ascii=True`. The second matters: a Japanese filename
  would otherwise travel as raw UTF-8 inside an HTTP header. `obsParamsJson`
  reproduces both and `ObsParamsTest` pins it.

Uploads are buffered whole in memory, so anything over 25MB (images) or 60MB
(video) is refused up front rather than risking the heap mid-send.

### Receiving

Attachments are not carried in the message — they live in object storage keyed by
message id, so a thumbnail is fetched lazily as each bubble first appears (three
at a time) and cached under `filesDir/media`.

Thumbnails are addressed differently depending on whether the chat is sealed, and
this is the part that catches people out:

| | Original | Thumbnail |
|---|---|---|
| plain | `/oa/r/talk/m/<messageId>` | `…/<messageId>/preview` |
| sealed | `/oa/r/talk/<SID>/<OID>` | `…/<SID>/<OID>__ud-preview` |

A sealed thumbnail is a **separate object**, not a path variant — the server
cannot resize what it cannot read, which is also why a dimension string like
`m800x1200` applies to plain media only. Asking for `<SID>/<OID>/preview` does
not fail; it returns an empty 200, which shows up as a blank bubble rather than
an error. `MediaUrlTest` pins all six combinations.

An empty response is never treated as success. Where a thumbnail genuinely is not
there, an image falls back to the original and lets Coil scale it — filing the
bytes under both keys so opening it costs no second download — while a video
shows a play badge instead, since pulling a whole clip down to draw a 220dp
bubble would be absurd and could not be rendered as a still anyway. A freshly
uploaded object has no thumbnail for a second or two, so a failure on a message
less than two minutes old is retried a few times.

Tapping opens the original: images full-screen in-app, video handed to whatever
the device already plays video with, through a `FileProvider` grant.

In a **sealed** chat the object is encrypted, lives under the namespace and
object id the sender recorded in `contentMetadata`, and the fetch must be
authorised with an `X-Talk-Meta` header — base64 of JSON wrapping a base64
TBinary struct. The file itself is AES-CTR under HKDF-SHA256
(`info="FileEncryption"`) of key material that only exists inside the decrypted
message body, with an appended HMAC that is stripped only when it verifies.

`MediaCryptoTest` pins the HKDF derivation, the CTR keystream, the MAC check and
the `X-Talk-Meta` bytes against the Python client — a wrong derivation decodes to
noise and says nothing about why.

## Message delivery

There is no push channel — LINE delivers through its own app, not ours — so the
client polls in a foreground service. That is why there is a permanent
low-priority "Connection" notification; Android will not keep a long-lived socket
alive otherwise.

**The working route is `/P3` + `fetchOperations`**, established by probing a live
DESKTOPWIN session. Neither reference implementation gets this right for this
device type:

| Endpoint | Method | Result on a live account |
|---|---|---|
| `/P3` | `fetchOperations` | **works** — what this client uses |
| `/P4` | `fetchOperations` | rejected |
| `/P5` | `fetchOps` | what CHRLINE uses; replies in TMoreCompact, no decoder here |
| `/P4` | `fetchOps` | what the Python client uses; `invalid method name: "fetchOps"` |

That last row is why the Python client's `listen()` never worked despite being
marked only "lightly tested": `/P4` speaks TCompact perfectly well, it just does
not host that method, and the error was being swallowed by the decoder.

The client still probes the list in order rather than hard-coding `/P3`, so a
future move gets diagnosed instead of silently breaking. **Settings → Connection**
shows the route in use.

`/P3 fetchOperations` is a **short-poll**: it answers immediately with an empty
list when nothing has happened, rather than holding the request open. That is
fine, just not a long-poll — but it has to be detected rather than assumed, since
a genuine long-poll also returns quickly when there is a backlog. Three
consecutive empty answers inside a second is the signal; after that the calls are
spaced 5s apart instead of hammering, which is close enough to live and well
clear of the request rate that
[gets accounts banned](https://github.com/DeachSword/LINE-DemoS-Bot/issues/1).
A 2s floor applies either way, so no endpoint can make the loop spin.

### The safety net

Delivery does not depend on any of that working. A poll with nothing to say and
one that is silently broken look identical from the client — both just sit there
— so instead of trying to tell them apart, the service also checks on a timer,
always:

- every 25s it calls `getLastOpRevision`, a **single** request that says whether
  the account moved at all;
- only when it has does it pay for the per-chat sweep;
- and it stands down entirely while the poll is visibly delivering — any op in
  the last 60s — so a healthy connection costs almost nothing.

That cheap revision check matters: LINE
[bans accounts that poll hard](https://github.com/DeachSword/LINE-DemoS-Bot/issues/1),
so a naive per-chat sweep on a timer would not be safe.

On top of that, the conversation on screen is re-fetched every 3s. It costs one
request and stops the moment the app is backgrounded, so it buys fresher messages
where they are being read without adding steady background load.

**Settings → Connection** shows when the safety net last ran, which is the
quickest way to tell whether background delivery is alive.

### Across process death

The last message id seen in each chat is written to disk, not just held in
memory. Without that a restarted process cannot tell a backlog from a first
sweep, and the only safe reading of "every chat looks new" is to stay quiet —
so anything that arrived while the app was dead used to vanish silently. With
the ids persisted the first sweep after a restart knows exactly which messages
are genuinely new and notifies for those alone.

### When the poll is down

Delivery falls back rather than stopping. The service keeps messages moving over
plain TalkService refreshes every 10s, stays there for 10 minutes, and only then
re-probes the candidate list — deliberately *not* between every refresh, because
a single hung candidate costs minutes and starves the fallback it was meant to
be checking on.

A failure that is really a **dead token** is caught before any of that: the
session is rebuilt and the poll resumes, since dropping to the fallback would
only fail the same way.

While degraded, the app shows a banner reading "Live updates unavailable —
checking periodically instead"; tapping expands the per-candidate probe results
and long-pressing copies them. The service notification reads "Checking every
10s", and **Settings → Connection** carries the same detail behind a Copy
button. So does logcat:

```bash
adb logcat -s PollingService:* LineClient:*
```

### Notifications

One notification per chat, `MessagingStyle`, so a busy conversation stacks its
last few lines instead of replacing them. Tapping opens that chat; opening it in
the app dismisses it. **Settings → Notify on new messages** turns them off
without touching the connection.

Announced message ids are remembered per chat — 64 of them, a few minutes' worth
— and that check lives in the notifier rather than in each caller. There is more
than one route to the same message: a redelivered op, or a safety-net sweep that
was already in flight when the poll delivered it. Each sender dedupes its own
path, but only the notifier sees all of them, and a second post under the same id
re-alerts rather than stacking — one notification that made the sound twice.

### Stopping it

**Quit app**, at the bottom of Settings, stops the service and exits. It is the
only way to put the connection down without signing out, because Android does not
let you dismiss a foreground service notification.

`PollingService.stop` goes through `stopService` rather than delivering itself an
intent: that clears the started state at the framework, so `START_STICKY` does
not resurrect the service the moment the process exits.

### Foreground service type

None of the documented types fits a third-party messaging client, so the choice
is between bad options. `dataSync` sounds closest but is **budgeted at six hours
a day** on Android 14+, after which the system stops the service and messages
simply stop arriving until the app is reopened — and there is no push channel to
cover the gap, because LINE delivers through its own app. A messaging connection
cannot be part-time, so the service is declared **`specialUse`** with a
`PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining why.

`specialUse` is not supposed to carry a runtime budget, but `onTimeout` is
implemented anyway (Android 15+): if the platform ever does time the service out,
it stops cleanly and the banner reads "Background time limit reached — reopen the
app". Ignoring that callback does not buy more time — it gets the process killed
outright with `RemoteServiceException`, which loses the connection and takes the
app down with it.

Note that `specialUse` is a declaration Google Play reviews for Play Store
distribution.

## Tests

```bash
./gradlew :app:testDebugUnitTest
```

83 unit tests, and they are worth keeping. The Thrift, crypto and media vectors
are byte-for-byte outputs of the Python reference implementation running on the
same inputs — LINE ships no IDL, and none of this fails loudly at runtime. A
wrong shared secret just makes every other device report your messages as
undecryptable; a wrong legy MAC gets an empty HTTP 200 with no explanation.

| Suite | Covers |
|---|---|
| `ThriftTest` | TCompact/TBinary encoding against Python output |
| `CryptoTest` | X25519, AES-GCM with a 16-byte nonce, legy bodies |
| `MediaCryptoTest` | HKDF, AES-CTR, the media MAC, `X-Talk-Meta` |
| `MediaUrlTest` | plain vs sealed object addressing, all six cases |
| `ObsParamsTest` | `X-Obs-Params` framing, including non-ASCII filenames |
| `CredentialBlobTest` | login field framing, ASCII and non-ASCII |
| `BatchBackoffTest` | MID batch back-off on `INVALID_LENGTH` |
| `XxHash32Test` | the legy body MAC |
| `MessageOrderTest`, `MessageMergeTest`, `StickerTest` | UI-facing message rules |

To regenerate a vector, run the equivalent call in `LINE_Chrome_Python` and
compare hex.

## Known limits

Found here, and worth fixing in the Python client too:

- **The login blob length prefix counts characters upstream, bytes here.**
  `_rsa_encrypt` frames each credential field with `chr(len(value))`, a character
  count. For ASCII the two agree, so an English account never notices — but a
  Japanese password declares 6 where it sends 16 and the login is rejected.
  `CredentialBlobTest` pins both cases.
- **MID batch sizes are found by backing off, not hard-coded.** `getChats` and
  `getContactsV2` take the whole list in one struct and answer
  `[code=6] Invalid Length` when it is too long, without saying what the limit is.
  The reference client's fixed 200 works only for a small account; a few hundred
  contacts and the friend list never loads at all. This port starts at 100 and
  halves until the server accepts.

Inherited from the Python client, and documented at more length in
[`LINE_Chrome_Python/README.md`](LINE_Chrome_Python/README.md):

- **Sending to a letter-sealed group fails** with `[code=99] old group key`. The
  plain send is refused outright rather than answered with `E2EE_RETRY_ENCRYPT`,
  so there is no fallback to take. Group sealing needs `registerE2EEGroupKey`,
  which neither client implements. Sealed groups can still be read.
- Messages this client sends may show "can't be decrypted" on your other devices.
  It signs with the key id lifted from the key chain rather than registering its
  own via `registerE2EEPublicKey`. Not fully diagnosed.
- Key rotation is one-way: `negotiateE2EEPublicKey` returns only a peer's current
  key, so messages predating a rotation stay unreadable. Keys are cached to limit
  future loss; they cannot be recovered retroactively.
- The chat list costs one `getRecentMessagesV2` per chat, eight at a time, since
  LINE has no call that returns it directly. Fine for tens of chats, slow for
  hundreds.
- TMoreCompact has no decoder here, which is why `/P5` is unusable even though it
  is what CHRLINE polls.

## Disclaimer

Unofficial, built by reverse-engineering. It violates LINE's terms of service and
can get your account restricted or banned. Use an account you can afford to lose.
No warranty.
