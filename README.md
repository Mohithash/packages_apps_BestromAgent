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

* Not autonomous. There is no trigger, no loop, no schedule. Every acting method
  needs `confirm: true` on the wire, and a human is on the other end.
* Not networked. The bridge is a unix abstract socket. The app holds no
  `INTERNET` permission and a verify gate asserts its absence.
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
| `agent.pair` | none | no | six digit code from the phone screen, single use, returns a token |
| `agent.auth` | none | no | re-authenticate with the token already held |
| `functions.list` | session | no | `searchAppFunctions`, AppSearch as the fallback |
| `functions.execute` | session | yes | a `PendingIntent` in the extras is reported, never launched |
| `ui.tree` | session | no | active window only, compact JSON, password nodes without text, desc or hint |
| `ui.tap` | session | yes | node action first, gesture fallback |
| `ui.long_press` | session | yes | |
| `ui.swipe` | session | yes | display pixels |
| `ui.type` | session | yes | never echoes the text, refuses password fields |
| `ui.key` | session | yes | back, home, recents, notifications, quick_settings, lock_screen, power_dialog, dismiss_notification_shade |
| `ui.screenshot` | session | no | PNG, base64; secure areas are blacked out, not refused |
| `app.launch` | session | yes | package, component or intent_uri, exactly one; rebuilt, rate limited |
| `app.list` | session | no | |
| `log.list` | session | no | the audit log |
| `log.clear` | session | yes | all or nothing |
| `agent.stop` | session | yes | runs the full off sequence |

Error codes beyond the JSON-RPC four: `-32001` unauthenticated, `-32002` bad
pairing code, `-32003` confirm required, `-32004` agent disabled, `-32005` device
locked, `-32006` user interacting, `-32007` rate limited, `-32008` node not
found, `-32009` action failed, `-32010` app function error, `-32011` stale tree,
`-32012` secure window, `-32013` screenshot unavailable, `-32014` timeout,
`-32015` not installed.

## The threat model

Defended, independently of the signing key:

* No network reachability, no `INTERNET` permission.
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

Not defended: indirect prompt injection. Everything `ui.tree` and `ui.screenshot`
return is text an attacker can put on the screen. Phase 1 keeps that risk on the
host by refusing to be autonomous at all. Screen content is data, never
instructions.
