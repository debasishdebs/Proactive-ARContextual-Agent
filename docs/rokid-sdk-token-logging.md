# Rokid CXR SDK logs the glasses auth token in plain text

For: Rokid CXR SDK maintainers. From: the Proactive Context app team. Found in device testing on 2026-10-14 (SEC-1).

## What

`com.rokid.cxr:client-l:1.1.2` writes the Hi Rokid authorization token to logcat in full:

| Where (class, method) | Call | Logged text |
|---|---|---|
| `com.rokid.sprite.aiapp.externalapp.example.ExternalAppClient.connect(String)` | `android.util.Log.i("CXRLink", "connect by token:" + token)` | the whole token, on every connect |
| `com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper` (authorization result) | `android.util.Log.d(..., "...token:" + token)` | the whole token, on every authorization |

We found these by reading the bytecode (`javap -c`). Both calls go straight to `android.util.Log`. Neither is gated by `com.rokid.cxr.link.utils.LogUtil`. `LogUtil.setLogLevel(int)` exists, but no code in client-l 1.1.2 calls `LogUtil`, so an app can't turn these lines off. `cxr-service-bridge:1.5` (`RLog`) has no level setter either.

`CxrSessionImpl` logs only `token.len=`, which is fine.

## Impact

- Anything that can read the device log can see the token. That includes adb or a bug report, a log-collecting app on older Android versions or with READ_LOGS, and our own users' diagnostics exports. With the token, someone can open a CXR-L session to the user's glasses as our app, using the camera, mic and HUD.
- The line appears on every connect, including automatic reconnects and the glasses-app watcher, so one bug report almost always contains it.
- An app can't work around this. Our in-app log viewer and export drop any line matching `token:`. That doesn't protect adb or system bug reports.

## Ask

1. Remove the token from both log lines, or log only its length or a short hash, as `CxrSessionImpl` already does.
2. Send all SDK logging through one switch, such as `LogUtil` or a public `CxrSdk.setLogLevel`. Make it default to off (or ERROR) in release AARs.
3. Ship this in a client-l release after 1.1.2 and add it to the changelog, so integrators know to rotate tokens.

Workaround on our side, until then: `CxrGlassesLink` calls `LogUtil.setLogLevel(LogUtil.ERROR)` at start in every build type, in case a later SDK routes these lines through it. Our own code never logs the token.
