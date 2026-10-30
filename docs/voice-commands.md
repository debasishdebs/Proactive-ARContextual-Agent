# Voice commands: pause and resume capture

Say one of these phrases as the whole utterance, from any surface that takes text: glasses voice, phone voice and the phone chat. All three go through the same backend (`Utterance.handle`), so the answer is the same everywhere. No model call is made, and they work while capture is paused.

| Say | Does |
| --- | --- |
| `pause capture`, `pause capturing`, `stop capturing`, `pause the camera` | Pauses capture (an indefinite pause until you resume) |
| `resume capture`, `resume capturing`, `start capturing`, `unpause` | Resumes capture |

A "please", "ok", "okay", "hey", "um", "uh" or "so" in front, or "please" at the end, is fine. Anything else in the sentence means it is not a command: "why did you pause", "pause capture and mark this" and "pause" alone all go to Ask as ordinary text.

## Replies

The reply says what really happened: "Capture paused", "Capture resumed", "Already paused", "Capture isn't paused" (a resume with nothing paused) or "Couldn't pause capture. Try again." / "Couldn't resume capture. Try again." if the change was not saved.

## Notes

- Voice pauses are recorded as PHONE pauses, whichever surface you used; `Hub.pausePhone` is the only entry that creates one. Resume ends the pauses stored at that moment, like the notification's Resume.
- A surface whose microphone is switched off in Settings cannot send voice, so it gets the "mic is off" reply instead.
- A double-tap on the glasses touchpad already toggles pause and resume; voice is an addition, not a replacement.
- Mark commands are in [voice-marks.md](voice-marks.md). Phrases live in `CaptureCommandParser` (`:services:voice`).
