# Message delivery

How this client finds out that a message arrived, why there are three separate
mechanisms for it, and which knobs change what.

The short version: LINE offers no push channel a third-party client can use, so
everything here is polling in one form or another, and the whole design is
shaped by one constraint — LINE
[restricts accounts that poll hard](https://github.com/DeachSword/LINE-DemoS-Bot/issues/1).
Every cadence below is a compromise between "messages arrive quickly" and "this
account still exists tomorrow".

## Why there is no push

Push notifications for LINE go to LINE's own app, through its own FCM
registration. There is no API that hands a third-party client a push channel,
and no way to register for one. So the client asks, repeatedly, forever — and
that is why there is a permanent low-priority "Connection" notification. Android
will not keep a long-lived socket alive in the background without a foreground
service.

## The three paths

Delivery does not depend on any single mechanism working. Three run
independently, and each can be turned off on its own:

| Path | Runs | Driven by | Costs |
|---|---|---|---|
| **Long-poll** | continuously, while it works | `pollLoop` → `liveSession` | one open connection |
| **Fallback** | only after the long-poll throws | `pollLoop` → `fallbackSession` | 1 request per tick, plus a sweep |
| **Safety net** | always, on its own coroutine | `syncLoop` | 1 request per tick when idle |

All three live in `service/PollingService.kt`. The distinction that matters:

- the **fallback** is *reactive* — it exists because the long-poll failed, and
  `pollLoop` hands over to it;
- the **safety net** is *unconditional* — it runs in parallel even when the
  long-poll looks perfectly healthy, because a long-poll that is silently broken
  and one that simply has nothing to say are indistinguishable from here.

```
PollingService
├── pollLoop()          long-poll ⇄ fallback, one or the other, forever
│   ├── liveSession()   holds the poll open; throws when it stops working
│   └── fallbackSession() TalkService refreshes for a while, then re-probe
└── syncLoop()          the safety net, independent of both
```

A fourth loop, `watchOpenChat` in `data/LineRepository.kt`, refreshes the
conversation currently on screen. It is not a delivery path — it makes the chat
you are reading fresher, and stops the moment the app is backgrounded.

## Long-poll and short-poll

Both are ways to fake server-push over ordinary HTTP request/response.

```
short-poll                          long-poll
client: anything new?               client: anything new?
server: no                          server: ...............(holds)
        (sleep 5s)                          ...............
client: anything new?                       ......[msg!]
server: no                          server: here
        (sleep 5s)                  client: anything new?   ← immediately
client: anything new?               server: ...............(holds)
server: here
```

**Short-poll** — the server always answers at once. Latency is up to the sleep
interval; cost is one request per interval forever, nearly all returning
nothing.

**Long-poll** — the server withholds the response until something happens or its
own timeout fires. Latency is near zero; cost is roughly one request per timeout
window, with the connection sitting open and blocked in between.

The mechanism is entirely in the HTTP read timeout. `line/LineClient.kt` keeps
several OkHttp clients for exactly this reason:

| Client | Read timeout | Used for |
|---|---|---|
| `http` | 30s | every normal TalkService call |
| `pollHttp` | **185s** | the long-poll |
| `probeHttp` | 25s | route probing only |

185s is the tell. The server holds a poll up to ~3 minutes and the client's
timeout sits just past that, so the client never gives up before the server
does. `probeHttp` is short on purpose: while probing, one candidate that accepts
a request and then sits on it must not hold up the candidates behind it.

## Finding the route

There is no way to ask LINE where the poll lives, and it has moved. So
`probePollRoute` tries `POLL_CANDIDATES` in order and keeps whichever answers:

| Endpoint | Method | Result on a live DESKTOPWIN account |
|---|---|---|
| `/P3` | `fetchOperations` | **works** — what this client uses |
| `/P4` | `fetchOperations` | rejected |
| `/P5` | `fetchOps` | what CHRLINE uses; replies in TMoreCompact, no decoder here |
| `/P4` | `fetchOps` | what the Python client uses; `invalid method name: "fetchOps"` |

That last row is why the Python client's `listen()` never worked: `/P4` speaks
TCompact perfectly well, it just does not host that method, and the error was
being swallowed by the decoder.

Probing rather than hard-coding `/P3` costs a few seconds once per session and
buys a diagnosis instead of a silent failure if LINE moves things again. The
per-candidate rejections are kept in `pollDiagnostics` and surfaced under
**Settings → Connection** behind a Copy button — they name the endpoint and
method the server rejected, which is the only thing that identifies the right
one.

`pollDiagnostics` is cleared the moment a route answers. Anything left in it
would read as a live complaint about a working connection.

### One prerequisite

`seedRevision()` runs before the loop and sets `revision = getLastOpRevision()`.
Every poll then means "operations after revision X". It is deliberately allowed
to throw: defaulting to revision 0 is not graceful degradation, it asks the
server for every operation the account has ever seen, and the poll then times
out forever.

## The twist: the working route is not a long-poll

`/P3 fetchOperations` answers **immediately** with an empty list when nothing has
happened. After all the long-poll plumbing, the one endpoint that works behaves
as a short-poll. That is fine — it delivers — it is simply not what the code was
built for, and it has to be *detected* rather than assumed.

The reason it cannot be assumed: **a genuine long-poll also returns instantly
when there is a backlog waiting.** A fast return proves nothing on its own.

So `liveSession` requires both conditions, and repetition:

- each round is timed; `elapsed < INSTANT_MS` (1s) **and** `ops.isEmpty()`
  increments a counter;
- any non-empty batch, or any round that actually blocked, resets it to 0;
- `SHORT_POLL_AFTER` (3) consecutive instant-empties switches to short-poll
  pacing and republishes `Connection.Live(route, shortPolling = true)`.

That is what makes Settings say "Connected, checking every few seconds" rather
than "Connected, live", and the service notification read "Connected (checking
regularly)".

### Pacing

```kotlin
val remaining = pace - elapsed
if (remaining > 0) delay(remaining)
```

Time already spent blocked on the server counts toward the gap. A long-poll that
held for three minutes sleeps zero before the next round; only a fast return
actually sleeps.

This is why the **long-poll floor** (2s) is a guard rather than a cadence — in
normal operation it never fires. It exists so an endpoint returning instantly
cannot spin the loop at network speed before the detector has had its three
rounds. The **short-poll interval** (5s) is a real cadence, and the one place
cost is continuous: one request every 5s, forever. That number is chosen against
the ban threshold, not against latency.

## When the poll is down

`liveSession` throws, and `pollLoop` decides what that meant.

**A dead token is caught first.** `LineServiceError` plus a successful
`recoverSession()` means the session was rebuilt and the poll simply resumes.
Dropping to the fallback here would be pointless — it would fail exactly the
same way, for the same reason.

Otherwise `fallbackSession` takes over: plain TalkService refreshes every 10s for
10 minutes, then return so the long-poll gets another chance. Re-probing happens
only on the way back round, deliberately *not* between every refresh — one hung
candidate costs minutes and would starve the fallback it was meant to be
checking on.

While degraded the app shows a banner, the notification reads "Checking every
10s", and **Settings → Connection** carries the probe detail behind a Copy
button.

## The safety net

`syncLoop`, and the one people find surprising. It runs on its own coroutine,
always, in parallel with everything above.

Per tick — every 25s by default — it skips out early if you are signed out, or
if an op arrived by long-poll within the last 60s (`lastOpAt` is stamped whenever
a non-empty op batch lands). A healthy connection therefore pays nothing at all.

Otherwise it calls `refreshViaTalkService()`, which is deliberately two-tier:

1. **one** request — `getLastOpRevision()`. If the revision has not moved since
   last time, it returns immediately. This is the normal case.
2. only if it *has* moved does it pay for the expensive part: refresh the
   on-screen chat, then `buildSummaries()`, which is one `getRecentMessagesV2`
   **per chat**, eight at a time.

That two-tier structure is the whole trick. A per-chat sweep every 25s would be
exactly the request volume that gets accounts banned; one cheap revision check
every 25s is affordable enough to leave running permanently.

It also stamps `_lastCheck`, surfaced as "Last background check" under
**Settings → Connection** — the quickest way to tell whether background delivery
is alive.

> Unrelated to Google's SafetyNet Attestation API. This is just the project's own
> name for the thing that catches messages when everything else fails silently.

## Across process death

The last message id seen in each chat is written to disk, not just held in
memory. Without that a restarted process cannot tell a backlog from a first
sweep, and the only safe reading of "every chat looks new" is to stay quiet — so
anything that arrived while the app was dead used to vanish silently. With the
ids persisted, the first sweep after a restart knows exactly which messages are
genuinely new and notifies for those alone.

Announced message ids are also remembered per chat (64 of them, a few minutes'
worth), and that check lives in `Notifier` rather than in each caller. There is
more than one route to the same message — a redelivered op, or a safety-net sweep
already in flight when the poll delivered it. Each path dedupes itself, but only
the notifier sees all of them, and a second post under the same id re-alerts
rather than stacking: one notification that made the sound twice.

## The cadences

Every interval above is a default, not a constant. **Settings → Polling** lists
all seven; they live in `data/PollingSettings.kt` and are stored on the same
SharedPreferences file as the session. The loops read their value once per tick,
so an edit applies on the next one — no service restart, no reopening a chat.

| Setting | Default | Range | 0 means |
|---|---|---|---|
| Long-poll floor | 2s | 1–60s | **off** — no long-poll at all |
| Short-poll interval | 5s | 2–300s | **off** — an endpoint that turns out to answer instantly is dropped for the fallback rather than short-polled |
| Fallback check | 10s | 5–600s | **off** — a failing poll is left to the safety net |
| Fallback session length | 10 min | 1–60 min | **one check**, then re-probe |
| Safety-net check | 25s | 10–600s | **off** |
| Safety-net quiet period | 60s | 10s–60 min | **never skip** |
| On-screen chat refresh | 3s | 1–120s | **off** |

Bounds are enforced on both read and write, so a stored value that predates a
change to a range cannot leak back out.

### What 0 means, and what it does not

Zero is accepted everywhere but does not mean the same thing everywhere. For the
five that drive requests it is an off switch. The other two are **waits**, not
cadences — a fallback session is how long to *stay* on the fallback before
re-probing, a quiet period is how recently the poll must have delivered before
the net skips a tick — so zero there means "do not wait", which is *more*
polling, not less.

The zero behaviour of each loop:

- **Long-poll floor 0** — `pollLoop` skips `liveSession` entirely and hands
  straight to the fallback. No probing, no polling.
- **Short-poll 0** — `liveSession` throws `ShortPollDisabled` at the exact moment
  of detection, so a route that proves to be a short-poller is abandoned rather
  than polled on a timer. The probe diagnostics are suppressed for this case:
  the route was fine and we walked away on purpose, so they would be misleading.
- **Fallback check 0** — `fallbackSession` makes no requests, but does not return
  either; see the backoff note below.
- **Safety-net check 0** — `syncLoop` idles.
- **On-screen refresh 0** — `watchOpenChat` idles.

Switched-off loops **idle rather than return**, ticking every 2s
(`IDLE_TICK_MS`). Turning one back on therefore takes effect on the next tick,
without restarting the service or reopening the chat.

### Turning delivery off entirely

Three of the seven are delivery paths: the long-poll floor, the fallback check
and the safety-net check (`PollingInterval.deliveryPaths`). Set all three to 0
and there is nothing left to run, so the service stops and its notification goes
with it. **Settings → Connection** then reads "Polling turned off in Settings",
and nothing arrives at all until one of the three goes back above 0 — which
starts it again. Settings shows this in a red card rather than letting you
discover it by missing messages.

`PollingService.start()` checks before starting, so the service is not raised
only to be torn down; `onStartCommand` checks again for the paths that do not
come through `start()`, but only *after* `startForeground`, because a service
launched with `startForegroundService` and then stopped without it is killed
with `ForegroundServiceDidNotStartInTimeException`.

### Constants that are not settable

Three deliberately stay fixed:

- **`INSTANT_MS` (1s)** and **`SHORT_POLL_AFTER` (3)** are not intervals. They
  are the detection threshold for "this endpoint is not really a long-poll", and
  exposing them would offer a knob that changes a diagnosis rather than a
  cadence.
- **`PROBE_BACKOFF_MS` (30s)** floors re-probing when the long-poll is on but the
  fallback is off. In that state nothing is left to pace the outer loop, and a
  route that fails fast would spin through the whole candidate list at network
  speed. This is the one place a 0 is not taken fully literally.

## Stopping it

**Quit app**, at the bottom of Settings, stops the service and exits. Android
does not let you dismiss a foreground service notification, so short of signing
out this and zeroing all three delivery paths are the two ways to put the
connection down — the difference being that Quit also closes the app, while a
zeroed poll leaves it running and receiving nothing.

`PollingService.stop` goes through `stopService` rather than delivering itself an
intent: that clears the started state at the framework, so `START_STICKY` does
not resurrect the service the moment the process exits.

## Foreground service type

None of the documented types fits a third-party messaging client, so the choice
is between bad options. `dataSync` sounds closest but is **budgeted at six hours
a day** on Android 14+, after which the system stops the service and messages
stop arriving until the app is reopened — with no push channel to cover the gap.
A messaging connection cannot be part-time, so the service is declared
**`specialUse`** with a `PROPERTY_SPECIAL_USE_FGS_SUBTYPE` explaining why.

`specialUse` is not supposed to carry a runtime budget, but `onTimeout` is
implemented anyway (Android 15+): if the platform ever does time the service out,
it stops cleanly and the banner reads "Background time limit reached — reopen the
app". Ignoring that callback does not buy more time — it gets the process killed
outright with `RemoteServiceException`, which loses the connection and takes the
app down with it.

`specialUse` is a declaration Google Play reviews for Play Store distribution.

## Diagnosing

**Settings → Connection** is the first stop: the route in use, whether it is
long- or short-polling, when the safety net last ran, and the per-candidate probe
output behind a Copy button when degraded.

```bash
adb logcat -s PollingService:* LineClient:*
```

What to expect in a healthy session:

```
LineClient     poll route resolved: /P3 fetchOperations (0 ops)
LineClient     starting from revision 1234567890
PollingService /P3 fetchOperations answers immediately; short-polling
```

And when it is not healthy:

```
LineClient     poll candidate /P3 fetchOperations rejected: ...
PollingService long-poll unavailable: /P3 fetchOperations -> ... | /P4 ...
PollingService fallback refresh failed: ...
```

## Source map

| File | What lives there |
|---|---|
| `service/PollingService.kt` | `pollLoop`, `liveSession`, `fallbackSession`, `syncLoop`, the fixed constants |
| `data/PollingSettings.kt` | `PollingInterval` (labels, defaults, bounds, zero semantics) and the store |
| `data/LineRepository.kt` | `refreshViaTalkService`, `applyOps`, `watchOpenChat`, `lastOpAt`, `lastCheck` |
| `line/LineClient.kt` | `fetchOps`, `probePollRoute`, `POLL_CANDIDATES`, `seedRevision`, the OkHttp timeouts |
| `service/Notifier.kt` | per-chat notifications and the announced-id dedupe |
| `ui/screens/SettingsScreen.kt` | the Polling section and the Connection detail |
