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
| Read receipts — sending | manual only — never sent automatically |
| Read receipts — “Read” / “Read N” on your own messages | working, and restored on opening a chat |
| Links in messages | detected, blue, tap opens the default browser |
| Stickers | rendered from the sticker CDN |
| Send images and video | working, unsealed chats only |
| Receive images and video, sealed **and** unsealed | working, with thumbnails |
| Receive audio and files | labelled `[Audio]` / `[File]`; not downloaded |
| E2EE 1:1 — encrypt **and** decrypt | working |
| E2EE group — decrypt | working |
| E2EE group — encrypt | working, except in a group that has never been sealed — see [Known limits](#known-limits) |
| Background polling + notifications | working, see [Message delivery](#message-delivery) |
| Adjustable poll cadences | **Settings → Polling**, all seven; 0 turns a loop off |
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
                 PollingSettings (the poll cadences, user-overridable)
service/         PollingService (foreground) + Notifier
ui/              Compose screens, dark-only Material 3 theme
```

`LineRepository` is a process-wide singleton because the polling service and the
Activity have to share one message store, and a session can cost a PIN
confirmation to rebuild.

### Why the theme provides a content colour

`LineChromeTheme` provides `LocalContentColor` explicitly. That looks redundant
next to a colour scheme that already names `onBackground`, and it is not:
material3 defaults `LocalContentColor` to **black** and expects a `Surface` to
have replaced it. A bare `MaterialTheme` never does.

Text that names its own colour is unaffected, and so is text inside a `Scaffold`,
an app bar or a `Surface`, since those provide one. Everything else was drawing
black on a near-black background — "Nothing open" measured 0 against a
background of 17, about 1.1:1, and most of the **Settings** labels were doing
the same.

It is provided here rather than by wrapping the app in a `Surface`, which would
also add a background draw and another pointer-input participant beneath
everything when the only defect is the colour.

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

### Back navigation

Back unwinds one layer at a time — media viewer, then profile, then
conversation, then the app — and each layer is a **predictive back** gesture:
drag from the edge and the surface shrinks and slides toward the swipe before
you commit, so you can see what letting go will do and cancel by dragging back.

This is `SeekablePanes` in `ui/AppRoot.kt`. Seekable rather than
fire-and-forget: the transition a tap plays through is the same one the gesture
drives by hand, so letting go part-way rewinds it rather than having to jump.

**Both layouts use it.** Stacked, the pane covers the window and the thing
underneath is the list. Side by side the list stays put and the gesture is
scoped to the detail pane alone — the conversation slides off toward the right
edge and the empty state comes up underneath it, or the conversation a profile
was opened from does. The surface leaving is smaller, but it leaves in the same
direction, so back feels the same whichever way the device is open.

That is a change: the two-pane layout used to take a plain `BackHandler` on the
grounds that with both panes on screen there was nothing to reveal. There is —
clearing a conversation reveals the empty state, and closing a profile reveals
the conversation.

Two things that only matter once the pane is narrower than the window, and both
showed up as the same symptom — a dark band drifting across the conversation
list:

- A sliding pane is **translated, not resized**, so it draws outside its own
  box. Full-screen that spill leaves the display and nobody sees it; in a pane
  it lands on the list. Hence `clipToBounds` on the detail pane.
- The dim darkens whatever the covered pane drew, which assumes it drew
  something. Every pane is an opaque `Scaffold` except the empty state, which is
  a bare `Box` — over that the black went straight onto the window. Hence the
  background on the pane container.

The awkward part is version behaviour, and it is worth being precise about.
`targetSdk` 36 turns predictive back on by default — but only on Android 16, so
the manifest opts in explicitly to get it on 13 through 15 as well. The
attribute is ignored below API 33, and `PredictiveBackHandler` itself carries no
`@RequiresApi`, so `minSdk` 24 is unaffected: where the platform has no
predictive back the progress flow simply emits nothing and completes on a plain
back press.

That last detail dictates how the handler is written. **The commit runs after
the collect, never from a progress threshold** — a threshold would never be
reached on a device that emits no progress, and back would stop working
entirely. Cancellation arrives as a `CancellationException` from the flow, which
is the gesture being released rather than the job dying, so it is caught and
used to settle the surface back to rest.

Opting in also means `onBackPressed()` is no longer called and `KEYCODE_BACK` is
no longer dispatched. Neither is used here — back has always gone through
`OnBackPressedDispatcher` — so there was nothing to migrate.

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

### “Read” on your own messages

Receiving is separate from sending, and not conditional on it: LINE pushes
`NOTIFIED_READ_MESSAGE` whether or not you ever send a receipt of your own, so
the ✓-button policy above costs you nothing here. Your messages show **Read** in
a 1:1 and **Read N** in a group, N being how many people have read that far —
LINE's 既読 and 既読N, in the language the rest of this app speaks.

A receipt is cumulative — one mark per reader, meaning "read up to here" — so
`_readReceipts` holds one entry per reader rather than one per message, and the
per-bubble count is worked back out in `readCounts`. That comparison is numeric
on the message id, not positional in the loaded list, because a mark often falls
outside the window: a reader further ahead than anything on screen still counts
for all of it, and one still behind the top counts for none. A mark that is not
a number cannot be placed either way and is dropped, which shows no label rather
than one on the wrong message.

Two things do not count: your own other devices reading the chat, and
`SEND_CHAT_CHECKED` echoing your own ✓ back at you.

### Where the marks come from

Two sources, merged by `mergeReadMarks`, newest mark always winning so neither
can drag a message back to unread:

- **live** — `NOTIFIED_READ_MESSAGE` off the poll, as people read;
- **backfill** — `getMessageReadRange`, on opening a chat.

The backfill is what makes the label survive a restart. Operations only report reads
that happen while the poll is running, so without it every mark vanished when
the app closed and did not return until somebody read something new — which,
since the poll seeds at the current revision, meant it was effectively never
visible.

`getMessageReadRange` takes `chatIds` at field **2** — the server names the
argument in its error but not the id, and 1, 3, 4 and 5 all still report it
missing. It answers per chat with a map of member to a *list* of ranges, each
`{1: startMessageId, 2: endMessageId, …}`. The furthest `endMessageId` wins:
reading is not always one contiguous run, but it is cumulative, so the highest
id anyone reached is how far they have got.

It runs alongside the message fetch rather than after it, so the label appears a
moment behind the bubbles instead of delaying them, and a failure is logged and
swallowed — the chat is readable either way.

### The soft spot

The third param of op 55 is meant to be the message id read up to. Observed in
a 1:1, where the first two params are both the other party:

```
p1=u9ac1f48…  p2=u9ac1f48…  p3=624950626019180681
```

But LINE has shipped that param empty and non-numeric depending on the client
the receipt came from. Anything unusable falls back to the newest message held
for that chat — which is what a bare receipt means in practice — and logs what
it actually saw, so a version that behaves differently shows up in logcat
rather than silently miscounting.

Only the 1:1 shape has been observed directly. A group receipt is expected to
name the group in `p1` and the reader in `p2`, and the code is written for that,
but it has not been caught in the act.

## Links

A URL in a message is blue and underlined, and tapping it opens the default
browser. Underlined as well as blue, because colour alone is not something
everyone can distinguish.

Detection is in `ui/components/Links.kt`, plain Kotlin with no Android
dependency — `android.util.Patterns.WEB_URL` is the obvious tool and is a
framework class that unit tests only see as a stub, and this is exactly the sort
of thing that wants tests.

Only `http://`, `https://` and bare `www.` are matched. **Bare domains are
not**: linking `example.com` means deciding that `Node.js`, `3.5` and
`README.md` are not domains, and a wrong guess turns an ordinary word blue and
sends you to a browser you did not ask for. Trailing punctuation is trimmed —
including `。` and `」`, which a Japanese sentence wraps a link in and `\S+`
swallows whole — while a bracket the URL itself opened is kept, so
`..._(planet)` survives.

### Why not LinkAnnotation

`LinkAnnotation.Url` is the built-in answer and brings its own tap handling. It
also *consumes the gesture*, and on a message that is nothing but a URL — which
is most links people send — the whole bubble becomes link, so long-press and
double-tap stop reaching it. Reply, Copy and Share would be unreachable on
exactly the messages most worth sharing. Verified on device before it was
abandoned: long-pressing a link opened Chrome instead of the menu.

So `LinkableText` styles the spans itself and owns all three gestures, resolving
a tap against the text layout to decide whether a URL was hit. A tap that misses
one does nothing, which is what tapping a message did before. The bubble keeps
its own `combinedClickable` for the padding around the text, which the text's
handler does not cover.

The cost is accessibility: these links are not announced as links by TalkBack,
which `LinkAnnotation` would have given for free.

## Letter sealing

Whether a chat is sealed is the *recipient's* setting and is not knowable up
front, so text goes out plain and is re-sent encrypted if the server answers
`E2EE_RETRY_ENCRYPT` (82). That is what LINE's own clients do.

1:1 derives a fresh secret per message: ECDH between our private key and the
peer's current public key, salted, AES-256-GCM with a 16-byte nonce.

Groups do not do this pairwise. The creator generates **one** key pair for the
group and hands each member a copy of the private half, wrapped with
ECDH(creator, member) — and, unlike message keys, with **no salt** in that
wrapping. Sending then pairs the group's private half with our *own* public half,
which is the same secret every member derives. `specVersion` is fixed at 2 rather
than negotiated, because there is no single peer to negotiate with.

The part worth knowing: **a group rotates its key whenever the membership
changes**, and there is no notification — the only way to learn our copy is stale
is to be refused with it, as `[code=99] old group key`. A cached key is therefore
dropped and refetched on that error and the send retried once, so a message into
a group somebody just joined or left goes through instead of failing.

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

> Full write-up in [`POLLING.md`](POLLING.md) — the three delivery paths, route
> probing, long- vs short-poll detection, and every cadence with its bounds.

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

Every interval quoted from here on is a **default**, not a constant — see
[Tuning the cadences](#tuning-the-cadences).

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
where they are being read without adding steady background load. (Internally this
is `watchOpenChat` and Settings calls it the *on-screen chat refresh* — "open" as
in the chat you have open, nothing to do with LINE's OpenChat, which this client
does not implement.)

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

### Tuning the cadences

Every interval above is a default rather than a constant. **Settings → Polling**
lists all seven and each is editable; the loops read their value once per tick,
so an edit applies on the next one without restarting the service or reopening a
chat.

| Setting | Default | Range | 0 means |
|---|---|---|---|
| Long-poll floor | 2s | 1–60s | **off** — no long-poll at all |
| Short-poll interval | 5s | 2–300s | **off** — an endpoint that turns out to answer instantly is dropped for the fallback rather than short-polled |
| Fallback check | 10s | 5–600s | **off** — a failing poll is left to the safety net |
| Fallback session length | 10 min | 1–60 min | **one check**, then re-probe |
| Safety-net check | 25s | 10–600s | **off** |
| Safety-net quiet period | 60s | 10s–60 min | **never skip** |
| On-screen chat refresh | 3s | 1–120s | **off** |

Zero is accepted everywhere but does not mean the same thing everywhere, and
this is the part worth reading twice. For the five that drive requests it is an
off switch. The other two are *waits*, not cadences — a fallback session is how
long to **stay** on the fallback before re-probing, a quiet period is how
recently the poll must have delivered before the net skips a tick — so zero
there means "do not wait", which is **more** polling, not less.

Three of them are delivery paths: the long-poll floor, the fallback check and
the safety-net check. Turn all three off and there is nothing left to run, so
the foreground service stops and its notification goes with it; **Settings →
Connection** then reads "Polling turned off in Settings" and nothing arrives at
all until one of the three goes back above 0, which starts it again. Settings
says so in a red card rather than letting you discover it by missing messages.

One floor is deliberately not settable. With the long-poll on but the fallback
off, nothing is left to pace the outer loop, so re-probing the candidate list is
held to 30s — a route that fails fast would otherwise spin.

The defaults are the values the rest of this section describes and sit well
clear of the request rate that
[gets accounts banned](https://github.com/DeachSword/LINE-DemoS-Bot/issues/1).
Lowering them is the direction to be careful in: there is no warning before a
restriction, and no way to appeal one on an account using an unofficial client.

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

**Quit app**, at the bottom of Settings, stops the service and exits. Android
does not let you dismiss a foreground service notification, so short of signing
out this and setting all three delivery paths to 0 are the two ways to put the
connection down — the difference being that Quit also closes the app, while a
zeroed poll leaves it running and receiving nothing.

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

- **A group that has never been sealed cannot be sealed by this client.** Sending
  into an already-sealed group works; *creating* the first group key needs
  `registerE2EEGroupKey`, which neither client implements. The signal is error
  code 5 from `getLastE2EEGroupSharedKey` — no key exists to fetch.
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
