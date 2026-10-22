# Task wear report
Status: done. Branch s3a/b.
- HudSettings +3 additive fields (wearParkEnabled=true, wearParkUnknownSec=120, wearParkWornSec=600), clamped 30..1800 s in normalized(); PROTOCOL_VERSION 1.
- WearDetector.config (WearParkConfig) read each second from settingsCache; enabled=false never parks by stillness; pc_wear unchanged.
- Phone: three fields in CaptureSettingsUiState/VM; WearDetectionCard (signal status, >3 min warning, battery text, steps, Open Hi Rokid for com.rokid.sprite.global.aiapp when installed, switch + two pickers). Signal known flag comes from the phone's own link.wearing (Hub.wearUnknownSince); no glasses message change.
- Canvas ParamSchema: NOT added (plans carry no device-global wear params; would need compile/validator/runtime machinery, and a strict schema entry would be silently ignored).
- Tests: new 5 WearDetector, 2 wire/clamp, 7 phone; core:model 160, glasses all, phone CaptureSettingsTest pass. Mutations caught: enabled flag (2 fail), custom thresholds (2 fail), clamp (1 fail), default-parse (1 fail).
- Note: written impl-with-tests together; RED shown by compile failure only.
