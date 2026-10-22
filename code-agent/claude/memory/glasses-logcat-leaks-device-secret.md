---
name: glasses-logcat-leaks-device-secret
description: "Rokid AssistServer writes deviceKey/deviceSecret and call numbers into the glasses logcat; always use the extended filter, never print DevCmdHelper lines"
metadata:
  node_type: memory
  type: feedback
  originSessionId: a6a5b53b-c399-4266-98de-c72503e9f379
  modified: 2026-10-15T07:31:33.536Z
---

The glasses' logcat contains lines from `com.rokid.os.sprite.assistserver` (DevCmdHelper jsonObject) with `deviceKey` and `deviceSecret`, plus phone numbers and contact names (PhoneCallManager, TelephoneManager, BluetoothPBAPManager). On 2026-10-15 one grep printed such a line into the chat (device key in full, start of the secret).

**Why:** the user has been angry about secret exposure before ("if you expose it anymore am going to stop trusting you"); filtering only "token|AuthorizationHelper" is not enough for the glasses.

**How to apply:** every glasses logcat read: `grep -v -i -E "token|AuthorizationHelper|deviceSecret|deviceKey|secret|password|DevCmdHelper|PhoneCallManager|TelephoneManager|BluetoothPBAPManager"`, preferably select our own tags (PcCapture, PcUplink, PcGlasses, PcHud). Never `cut` a line from a grep of a wide pattern like "wearingStatus" without that filter. Never save unfiltered full logcat for agents to read. See [[never-print-secrets]].
