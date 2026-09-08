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
* Not networked. The bridge is a unix abstract socket reached only through
  `adb forward`. The app holds no `INTERNET` permission and a verify gate asserts
  its absence.
* Not resident. Both services ship `android:enabled="false"`, there is no
  receiver, job, provider or notification listener, and Agent mode does not
  survive a reboot. With the switch off the app is an APK on disk.

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
| `agent.pair` | none | no | six digit code from the phone screen, returns a token |
| `agent.auth` | none | no | re-authenticate with the token already held |
| `functions.list` | session | no | `searchAppFunctions`, AppSearch as the fallback |
| `functions.execute` | session | yes | a `PendingIntent` in the extras is reported, never launched |
| `ui.tree` | session | no | active window only, compact JSON, password nodes without text |
| `ui.tap` | session | yes | node action first, gesture fallback |
| `ui.long_press` | session | yes | |
| `ui.swipe` | session | yes | display pixels |
| `ui.type` | session | yes | never echoes the text, refuses password fields |
| `ui.key` | session | yes | back, home, recents, notifications, quick_settings, lock_screen, power_dialog, dismiss_notification_shade |
| `ui.screenshot` | session | no | PNG, base64; the platform's own interval limit applies |
| `app.launch` | session | yes | package, component or intent_uri, exactly one |
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
  minute idle timeout.
* A confirmation floor the phone enforces itself, so a misbehaving client cannot
  act by accident.
* Hard stops the platform gives for free: a locked device, `FLAG_SECURE`
  surfaces and password fields are refused with distinct error codes, so
  "blocked" is never mistaken for "empty".
* An audit log the model cannot rewrite selectively: append only, bounded at 500
  entries, values never recorded, readable in Settings, clearable only wholesale.

Not defended: indirect prompt injection. Everything `ui.tree` and `ui.screenshot`
return is text an attacker can put on the screen. Phase 1 keeps that risk on the
host by refusing to be autonomous at all. Screen content is data, never
instructions.
