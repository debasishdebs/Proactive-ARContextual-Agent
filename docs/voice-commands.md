# Voice commands: pause and resume capture

Say one of these phrases as the whole utterance, from any surface that takes text: glasses voice, phone voice and the phone chat. All three go through the same backend (`Utterance.handle`), so the answer is the same everywhere. No model call is made, and they work while capture is paused.

| Say | Does |
| --- | --- |
| `pause capture`, `pause capturing`, `stop capturing`, `pause the camera` | Pauses capture (an indefinite pause until you resume) |
| `resume capture`, `resume capturing`, `start capturing`, `unpause` | Resumes capture |

A "please", "ok", "okay", "hey", "um", "uh" or "so" in front, or "please" at the end, is fine. Anything else in the sentence means it is not a command: "why did you pause", "pause capture and mark this" and "pause" alone all go to Ask as ordinary text.

## Replies

Every reply says only what was confirmed. "and the glasses" is said only when the settings push was accepted by the link; a connected link alone never counts. The phone has no signal for the glasses' low-battery capture hold, so no reply claims or denies one.

| Situation | Reply |
| --- | --- |
| Pause, push accepted | `Paused on the phone and the glasses` |
| Pause, push not accepted | `Paused on the phone; the glasses will follow when they reconnect` |
| Pause, already an indefinite phone pause | `Already paused by you` |
| Resume, push accepted | `Resumed on the phone and the glasses` |
| Resume, push not accepted | `Resumed on the phone; the glasses will follow when they reconnect` |
| Resume, a synchronised glasses pause remains | the resume reply above + `. The glasses are still paused` |
| Resume, quiet hours remain | the resume reply above + `. Quiet hours are still on` |
| Resume, no phone pause, nothing else known | `No pause is set on the phone` |
| Resume, no phone pause, glasses pause known | `No pause is set on the phone. The glasses are still paused` |
| Not saved | `Couldn't pause capture. Try again.` / `Couldn't resume capture. Try again.` |

## Notes

- Voice pauses are recorded as PHONE pauses, whichever surface you used; `Hub.pausePhone` is the only entry that creates one. Resume ends the pauses stored at that moment, like the notification's Resume.
- A surface whose microphone is switched off in Settings cannot send voice, so it gets the "mic is off" reply instead.
- A double-tap on the glasses touchpad already toggles pause and resume; voice is an addition, not a replacement.
- Mark commands are in [voice-marks.md](voice-marks.md). Phrases live in `CaptureCommandParser` (`:services:voice`).
