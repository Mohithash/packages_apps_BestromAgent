# BestromAgent

Agent mode for BestROM. A platform-signed privileged `system_ext` app that lets a
computer connected over adb read the phone's screen, act on it and call the app
functions Settings and other apps publish.

It is a **maintainer tool**, not a shipped user feature. BestROM images are
currently signed with VoltageOS's public `vendor_voltage-priv_keys`, so anyone
can build an APK that claims to be `com.bestrom.agent`, matches the platform
certificate and inherits `EXECUTE_APP_FUNCTIONS` and `WRITE_SECURE_SETTINGS`.
Every privilege here is exactly as strong as that key. Calling Agent mode a
feature is gated on the key rotation, not on this code.

## What it is not

* Not triggered. There is no schedule, no receiver and no notification
  listener. A task starts when a person types one into the field on the task
  screen, and never any other way.
* Not networked except to the one endpoint the user configured. The bridge is
  still a unix abstract socket with no address outside the device. `INTERNET`
  is held by exactly one class, `brain/OpenAiCompatClient`, which builds one
  URL and no other; a verify gate asserts the dex carries the platform HTTP
  stack and carries no vendored one.
* Not resident. Both services ship `android:enabled="false"`, there is no
  receiver, job, provider or notification listener, and Agent mode does not
  survive a reboot. With the switch off the app is an APK on disk.
* Not a confused deputy for `app.launch`. `Intent.parseUri` also accepts
  extras, categories and flags; only the action, data, component and package
  survive, the flags start at zero, and `FLAG_ACTIVITY_NEW_TASK` is the only
  one added. The screen itself is behind `WRITE_SECURE_SETTINGS` and its root
  view sets `filterTouchesWhenObscured`, so the switch cannot be tapjacked.

## Turning it on

Settings > Custom Tweaks > Advanced > Agent mode, then the switch. The order the
app uses is load bearing:

1. `POST_NOTIFICATIONS` must be granted; nothing else runs without it.
2. Enable `AgentBridgeService`.
3. Start it in the foreground. It binds `LocalServerSocket("bestrom_agent")`,
   sets the live flag, generates a six digit code and posts the ongoing
   notification.
4. Enable `AgentAccessibilityService`.
5. Append it to `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` and set
   `ACCESSIBILITY_ENABLED`.

Reversing 3 and 4 makes the accessibility service disable itself the moment it
binds, which is also the reboot cleanup: with no bridge behind it,
`onServiceConnected()` removes the component from the secure setting, disables
both components and unbinds.

## The wire

    adb -P 15038 -s <serial> forward tcp:8765 localabstract:bestrom_agent

Then connect a plain TCP socket to `127.0.0.1:8765` and speak newline-delimited
JSON-RPC 2.0: exactly one object per line, terminated by a single `0x0A`. No
batches, no notifications. Requests are capped at 1 MiB, responses at 8 MiB.

    -> {"jsonrpc":"2.0","id":1,"method":"agent.hello","params":{"client":"mcp","protocol":1}}
    -> {"jsonrpc":"2.0","id":2,"method":"agent.pair","params":{"code":"418302"}}
    -> {"jsonrpc":"2.0","id":3,"method":"ui.tree","params":{}}

| method | auth | confirm | notes |
| --- | --- | --- | --- |
| `agent.hello` | none | no | device and capability report, no user content |
| `agent.pair` | none | no | six digit code from the phone screen, single use, returns a token and `code_expires_utc` |
| `agent.auth` | none | no | re-authenticate with the token already held |
| `functions.list` | session | no | `searchAppFunctions`, AppSearch as the fallback with a `fallback_reason` |
| `functions.execute` | session | yes | a `PendingIntent` in the extras is reported, never launched |
| `ui.tree` | session | no | active window and visible nodes only, password nodes without text, desc or hint |
| `ui.tap` | session | yes | node action first, gesture fallback; answers `via` |
| `ui.long_press` | session | yes | same result shape as `ui.tap` |
| `ui.swipe` | session | yes | display pixels |
| `ui.type` | session | yes | never echoes the text, refuses password fields |
| `ui.key` | session | yes | back, home, recents, notifications, quick_settings, lock_screen, power_dialog, dismiss_notification_shade |
| `ui.screenshot` | session | no | PNG, base64 always; secure areas are blacked out, not refused |
| `app.launch` | session | yes | package, component or intent_uri, exactly one; rebuilt, rate limited |
| `app.list` | session | no | |
| `log.list` | session | no | the audit log |
| `log.clear` | session | yes | all or nothing |
| `agent.stop` | session | yes | runs the full off sequence |

Four result shapes are worth naming, because the host mirrors them exactly:
`ui.tap` and `ui.long_press` both answer `{ok, via, target}`, where `via` is
`"node"` or `"gesture"` - it is not called `method`, which is the JSON-RPC
method name. `agent.pair` returns `code_expires_utc`, the expiry of the six
digits and not of the pairing. `functions.list` always sends `parameters` and
`response` as JSON arrays, or leaves them out, so a one-parameter function is
not shaped differently from a two-parameter one, and it carries
`fallback_reason` when the AppSearch path ran because the manager path failed.
There is no `encoding` other than `base64` and no `include_invisible`.

Error codes beyond the JSON-RPC four: `-32001` unauthenticated, `-32002` bad
pairing code, `-32003` confirm required, `-32004` agent disabled, `-32005` device
locked, `-32006` user interacting, `-32007` rate limited, `-32008` node not
found, `-32009` action failed, `-32010` app function error, `-32011` stale tree,
`-32012` secure window, `-32013` screenshot unavailable, `-32014` timeout,
`-32015` not installed. `-32012` covers a password field and an excluded
package (`data.reason`); no active window is `-32004` with
`data.reason = "no_active_window"`, which is a state and not a refusal.

## The threat model

Defended, independently of the signing key:

* One network destination: the chat-completions endpoint the user typed in,
  reached by one class. No other code in the app opens a connection, and
  nothing reaches the phone from outside.
* No persistence: nothing in the boot path, nothing restored after a reboot.
* No silent power: a code on the phone screen to pair, a token that lives in
  memory only, an ongoing notification, three independent stops, and a thirty
  minute idle timeout. The code pairs exactly once and a second `agent.pair` is
  refused while a token is out, so New code on the phone is the only way to
  hand the session to another client.
* A confirmation floor against a client that forgets, not against one that is
  hostile: the MCP host sets `confirm: true` on every acting call it makes, so
  the phone's own check is a floor under a buggy client, not a second gate.
* An authenticated peer. sepolicy does **not** make the socket adbd-only:
  `allow domain self:unix_stream_socket connectto` in `private/domain.te` lets
  any process in the same domain connect, and this app runs in `platform_app`,
  so every other platform-signed app can reach it. What enforces adbd is the
  bridge itself - it reads `getPeerCredentials()` at accept time and closes
  anything whose uid is not shell (2000) or root, counting the refusals on the
  Agent mode screen.
* Nothing an unauthenticated peer sends is written to the audit log: it would
  otherwise flush all 500 real entries by connecting. It gets a ten second
  handshake deadline, eight requests, and at most two of the four slots.
* Hard stops: the keyguard being up at all - `isDeviceLocked` **or**
  `isKeyguardLocked`, so a swipe-only lock and Smart Lock are both covered - and
  password fields, which are refused with a distinct error code so "blocked" is
  never mistaken for "empty".
* An audit log the model cannot rewrite selectively: append only, bounded at 500
  entries, values never recorded, the peer uid and connection recorded with
  every entry, readable in Settings, and clearable only wholesale - a clear is
  itself the first entry of the fresh log, from the wire or from the screen.

Not defended, and said plainly because the opposite was claimed here before:

* **`FLAG_SECURE` does not hide anything from `ui.tree`.** That flag governs
  screen capture, not accessibility; nothing in the platform's window or node
  path tests it. The screen tree of any app, including an app that blocks
  screenshots, is readable. There is no error code for it because nothing is
  refused.
* **`ui.screenshot` of a secure screen is not refused either** - it comes back
  with those areas blacked out by the platform. That redaction is why
  `android:isAccessibilityTool` is absent from the service xml: with it, and as
  a system app, this service would qualify for `canCaptureSecureLayers()` and
  the platform would hand it those layers in the clear. `-32012` is kept for the
  `ERROR_TAKE_SCREENSHOT_SECURE_WINDOW` case in the unlikely event the platform
  does produce it; every other screenshot failure is `-32013`.
* Password fields are the one real exclusion in the tree: `text`, `desc` and
  `hint` are omitted for a node whose `isPassword()` is true, and `ui.type`
  refuses one outright.
* For anything else, the exclusion has to be the maintainer's own: **Excluded
  apps** on the Agent mode screen is a package list, empty by default, and
  `ui.tree`, `ui.screenshot` and `ui.tap` refuse with `-32012`
  (`data.reason = "denied_package"`) while an excluded package is on screen.

Not defended, still: indirect prompt injection. Everything `ui.tree` and
`ui.screenshot` return is text an attacker can put on the screen. What the
runner adds is a filter for the mechanical channels and a policy engine that
does not read the model's opinion; neither of those makes the agent immune.
See "Prompt injection, honestly" below.

## The runner

Phase 1 made the phone a tool set for a computer. Phase 2 moves the caller onto
the phone: the same tools, the same guardrails, driven by a model the user
configures. Nothing about Phase 1 changed - `adb forward` still works, the
pairing code still works, and the runner is simply a second caller of
`Methods.dispatch`.

    goal -> read screen -> model -> policy -> act -> look again -> ... -> done

It runs on one thread inside `AgentBridgeService`, started when a task starts
and gone when it ends. There is no service for it, no job, no receiver and
nothing at boot; with Agent mode off the app is still an APK on disk.

The loop is deliberately short of tricks. There is no planner pass, because a
phone task is three to six steps and a plan is stale after the first surprise.
There is no parallel rollout, because the phone has one screen. The one
optimisation that earns its keep is that a fresh screen is attached to the
result of every acting tool, so the model does not spend a whole round trip
asking what happened - a four-action task is five or six model calls, not ten.

Portions of the runner - the loop structure, the post-action screen attachment,
the history compaction, the stuck detector and the task budget - are derived
from [PokeClaw](https://github.com/agents-io/PokeClaw), Copyright 2026
agents.io, licensed under the Apache License, Version 2.0. No file is copied;
the derivation is of structure, and `NOTICE` records it.

## The brain

One OpenAI-compatible chat-completions client, `brain/OpenAiCompatClient.kt`,
built on `HttpsURLConnection`. There is no vendored HTTP library:
`external/okhttp` in this tree is ART-internal and visibility-restricted, and a
verify gate asserts the dex contains `HttpsURLConnection` and contains no
`okhttp3` or `retrofit2`.

The user supplies the base URL, the key and the model. Presets fill in the first
and suggest the third:

| preset | base URL | notes |
| --- | --- | --- |
| Anthropic | `https://api.anthropic.com/v1` | `Authorization: Bearer`. Anthropic documents this layer as for testing rather than production, and it does **not** support prompt caching - which is the whole cost argument for Haiku. |
| OpenAI | `https://api.openai.com/v1` | the one preset that sends `max_completion_tokens`; `max_tokens` is deprecated there and rejected by the GPT-5 line |
| Gemini | `https://generativelanguage.googleapis.com/v1beta/openai` | cheapest major vendor, and the worst fit for a ROM that ships no GApps. Said out loud rather than hidden. |
| Groq | `https://api.groq.com/openai/v1` | free tier, no card, ~30 req/min, best latency anywhere |
| Cerebras | `https://api.cerebras.ai/v1` | ~1M free tokens a day, highest throughput measured |
| OpenRouter | `https://openrouter.ai/api/v1` | `HTTP-Referer` and `X-Title` are attribution-only and are **not** sent |
| Local llama.cpp | `http://<host>:8080/v1` | needs `--jinja` for tool calls and `--host 0.0.0.0` |
| Local Ollama | `http://<host>:11434/v1` | needs `OLLAMA_HOST=0.0.0.0`, and does not support `tool_choice`, so the preset omits the field |
| Custom | none; you type one | anything OpenAI-compatible. `tool_choice` is sent, `stream` is not |

Nine rows, because the LAN server is two: Ollama answers an error for
`tool_choice` and llama.cpp accepts it, and that is the one field that changes
the request.

**The plain-http rule is about the address, not about the preset.** Any preset
may speak plain http to a loopback, RFC1918, 100.64/10, 169.254/16 or `fc00::/7`
literal, and no preset may speak it to anything else. A *name* is never private,
however it is spelled: `localhost.attacker.example` resolves wherever its owner
says. The base URL is parsed once, with `java.net.URL`, and the same object
answers both the scheme question and the host question - a second, hand-written
parser that ends the authority one character earlier is how a key leaves in
cleartext to a host the check never saw. A base URL carrying `#`, `?`, `\`,
whitespace or a user name is refused outright.

Cleartext to a private address needs `res/xml/network_security_config.xml`: with
no config the platform refuses plain http to anything but loopback, and on this
API level `android:usesCleartextTraffic` does nothing at all. A network security
config cannot express a CIDR range, so the base config permits cleartext and the
address check above is the real gate. That is deliberate and the file says so.

`INTERNET` is the one permission this adds. It is `normal`, so the privileged
allowlist is unchanged - still exactly two entries. The client builds exactly one
URL, `<baseUrl>/chat/completions`, and there is no other network code in the app.

A key stored for a cloud preset is never sent to a LAN server: the two local
presets send the placeholder `local` and nothing else, whatever is in the store
when the preset changes.

The key is sealed with an `AndroidKeyStore` AES-GCM key created with
`setUnlockedDeviceRequired(true)`, and the ciphertext lives in the app's files
directory. It is never logged (the `brain` package contains no logging statement
and a gate asserts it), never audited and never rendered back into the field.
`allowBackup` is false, which on Android 12 and up covers cloud backup only, so
`dataExtractionRules` excludes the files directory from device-to-device
transfer as well. A key that is stored and will not unseal is its own sentence -
"unlock the phone and try again" - rather than the provider's 401 reported as a
bad key.

## The tools

Eleven, mapping one to one onto bridge methods, with the fields the model has no
business choosing filled in by the caller.

| tool | method | tier |
| --- | --- | --- |
| `read_screen` | `ui.tree` | read |
| `list_functions` | `functions.list` | read |
| `screenshot` | `ui.screenshot` | read, and absent from the schema unless the user enabled it |
| `tap` `long_press` `swipe` `type` `key` | `ui.*` | mutating |
| `launch_app` | `app.launch`, package form only | mutating |
| `call_function` | `functions.execute` | mutating |
| `done` | - | terminal |

`app.list`, `log.list`, `log.clear` and `agent.stop` are **not** in the schema.
The app list is put in the prompt once instead; the audit log is the
accountability control and a model that can read it can plan around it; stopping
is the user's.

`tree_id` is never shown to the model - the runner remembers it from the last
`read_screen` and supplies it, so a stale tree is caught by the platform rather
than papered over. The `component` and `intent_uri` forms of `app.launch` are not
offered at all: a model that can only name a package cannot build an Intent.

The screen is handed over as a digest, not as the raw tree - one line per element
instead of a JSON object per node, three or four kilobytes where the tree is
thirty to sixty.

The tool block itself is 3.2 KB with screenshots off, measured rather than
guessed, and it is re-sent on every step of every task - so the descriptions are
one line each and the working rules live in the system prompt, which is stated
once. Screenshots are one switch, not two: "Send screenshots (the model must
accept images)".

## The policy

Every call is classified from its name and its arguments. **What the model says
about a call is never an input**, because an app that can put text on the screen
can talk to the model.

* **read** - never asks.
* **mutating** - asks, unless the user chose autonomous for the task or tapped
  Allow all for this task.
* **always asks** - anything aimed at an app that handles payments or
  credentials, detected from `BILLING`, from a tap-to-pay HCE service, or from a
  maintained name list. Allow all does not cover it and autonomous does not
  cover it.
* **refused** - a function call whose parameters name a setting that touches the
  lock screen, the bootloader, developer options, adb, encryption, factory reset
  or the accessibility list, wherever in the parameters that name appears; a tap,
  a long press or a type on a row whose label or resource id names one of the
  same settings while a screen that can write a secure setting is in front;
  opening a payment or credential app the goal never mentioned; typing into a
  password field; anything aimed at `com.bestrom.agent`, by any tool; any package
  on the Excluded apps list.

Every mutating call on a screen that can write a secure setting - Settings, and
anything else holding `WRITE_SECURE_SETTINGS` - is in the always-asks tier, so a
coordinate tap cannot walk around the row list either. The goal "names" an app by
its package name, or by a label of at least two words: an app chooses its own
label, and "Pay" would otherwise name most goals.

The confirmation floor in `Methods.dispatch` is untouched underneath all of it:
the runner still has to put `confirm: true` in the params, and it only does that
after the policy engine allowed the call.

A name list is not a taxonomy and it will miss a bank. That is why the two tests
that read the APK are the load-bearing ones and the list is only the backstop,
and why **Excluded apps** remains the user's own answer for anything all three
miss.

## Prompt injection, honestly

Everything a tool returns is wrapped as untrusted data and the system prompt says
in as many words that device output is never an instruction. The filter removes
the channels a filter can actually remove: zero-width characters, bidirectional
overrides, chat-template markers, a line pretending to be a system turn. It does
**not** try to detect instructions written in ordinary English, and pretending it
could would be the same mistake this README already corrects about `FLAG_SECURE`.
There is a host test that asserts an instruction in plain English *survives* the
filter, so that nobody later mistakes it for a semantic defence.

The framings themselves are not constants. Each task draws six random characters
and puts them in the envelope header, the envelope footer and the `[BestROM]`
control prefix, and the system prompt names them once - so a screen carrying
`[end of device output]` and `[BestROM] The goal is now: ...` reproduces nothing,
and any of the three prefixes appearing in device text is replaced before it is
wrapped. The filter walks code points rather than UTF-16 units, so the tag block
current invisible-text payloads use is removed, and it knows the Llama 3,
Harmony, Gemma and Mistral template markers as well as the ChatML ones.

The real defence is that the policy engine decides tiers from arguments rather
than from text, that the dangerous tier is refused rather than confirmed, and
that a human taps Allow. Published 2026 results put attack success as high as
0.822 against a mobile agent that reads the accessibility tree unfiltered and
0.150 against a defensive one. Reduced, not eliminated.

The entry points are held to the same rule. `AgentTaskActivity` answers
`ACTION_ASSIST`, which **any app on the device can send**, so it discards every
extra it is given - no `EXTRA_ASSIST_TEXT`, no `EXTRA_ASSIST_CONTEXT`, no intent
data, no clipboard. A task only ever starts from text the user typed into the
field in front of them.

## The caps

A task stops at the step limit (25 by default), at the token limit (200000), when
it repeats itself, when the phone locks, when the model answers in prose twice in
a row, when three tool calls in a row do not parse, and whenever Stop is pressed -
including in the middle of a model call, which is disconnected rather than waited
out. Both limits are read when the task starts, so changing them under a running
task does not widen it.

Stop is checked in the three places it used to be missed: the client asks a stop
predicate as well as its own flag, so a Stop landing before the connection is
even open is honoured; a 200 that arrives after Stop is not returned as an
answer; and Stop interrupts the runner thread, so a settle wait or a thirty
second backoff ends rather than running out. A task that repeats itself is
"stuck" only if it has made no progress in between - a recovered hiccup no
longer counts towards it forever.

## Where the screen goes

While a task runs, everything the agent reads is sent to the endpoint the user
configured, with the user's key: the text of every screen it looks at, element
labels and resource ids, any notification text on screen, the name and label of
every installed app, and every app function the phone publishes. The app list and
the function catalogue go once per task even if the agent never opens those apps.
Screen contents go on every step as text, and as a PNG if screenshots are on.
Password fields are the one exclusion. Nothing is kept on the phone: no
transcript is written to disk, and the audit log records only the method, the
model id and why a task ended.

That paragraph is the intro on the Brain screen, above the first row rather than
under the last one, and the task screen keeps the line naming the endpoint on
screen while a task is running - which is when it matters. What the endpoint does
with what it receives is between the user and that provider; BestROM runs none of
them. A llama.cpp or Ollama server on the user's own network is the setup that
sends nothing off it.

The audit log gains four entries per task: `agent.start`, one `brain.call` per
model call with the model id as its target, `agent.refused` for every call the
policy engine refused, with the tool name as its target, and `agent.end` with the
reason the task ended. The goal is text the user typed, so it is not one of them,
and neither is anything that was on screen.

The step lines the task screen shows are cleared when the task ends - only the
ending line stays, because it is the answer - and the window sets `FLAG_SECURE`,
so what the agent read is not in the Recents snapshot either.
