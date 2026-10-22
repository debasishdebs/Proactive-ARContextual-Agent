# Voice marks

A voice mark saves "something worth remembering happened just now" without touching your phone. Say a short command and the app saves a mark for the last few seconds. This page is generated from the same phrase table the app uses, so it always matches what the app understands.

## How to mark

- Tap the glasses while the HUD is visible.
- Or press push-to-talk on the glasses or the phone, then say a command from the lists below.
- Marks from the phone notification, the tile or the home button are not available yet; they are planned for a later build.

Voice marks are always on in this build. A switch to turn them off is coming in a later build. Marking by tap on the glasses is always on in this build too, and has no switch yet. A tap only saves a mark and never changes when photos are taken. There is no hotword and no always-on listening. The app listens only after you press push-to-talk (or after a tap, on the voice path), and stops when you finish speaking.

## What you can say

Use any phrase below. A word like "please", "ok", "hey", "um", "uh" or "so" in front is fine. After a mark command you can add what you were doing, for example "mark that, I'm driving". Unknown words in a mark command's own sentence save a plain mark. For example, "mark that was great" is a plain mark. This holds for "mark that", "mark this" and "mark it". The phrases under "must stand alone" below, and "I missed that" and the undo phrases, are commands only when no other words follow in the same sentence, so "note that the meeting is at five" is a normal note for the assistant, not a mark.

Each sentence is read on its own. If you say a mark command and then something else, for example "mark that and what's this building", the mark is saved and the rest is answered as a normal question. Words that are not part of a command are never swallowed by it.

Commands: mark a moment, "I missed that" (saves the previous 10 seconds, and keeps an intent word if you add one) and undo. Input longer than about 500 characters is never treated as a command.

<!-- voice-marks:begin -->
#### Mark a moment
Saves a mark for the last 10 seconds up to the moment you pressed push-to-talk.
- `mark that`
- `mark this`
- `mark it`
- `remember this`
- `remember that`
- `save this`
- `save that`
- `note this`
- `note that`
- `bookmark this`
- `bookmark that`
- `capture this`
- `this is important`
- `that was important`

#### Phrases that must stand alone
These also start ordinary sentences or notes. They count as a mark only when nothing but an intent word follows, so "note that the meeting is at five" stays a note and goes to the assistant.
- `remember this`
- `remember that`
- `save this`
- `save that`
- `note this`
- `note that`
- `bookmark this`
- `bookmark that`
- `capture this`
- `this is important`
- `that was important`

#### I missed that
Does the same as a mark: saves the previous 10 seconds. It is not a mark if other words follow, so "I missed that bus" is a normal sentence.
- `i missed that`

#### Undo
Deletes the mark from this listen, or your newest voice or tap mark from the last 2 minutes.
- `undo mark`
- `cancel mark`
- `remove mark`

#### Polite starts you can add before any command
- `please`
- `ok`
- `okay`
- `hey`
- `um`
- `uh`
- `so`

#### Intent words (what you were doing)
Add one after a mark command, for example "mark that, I'm driving". With "mark that", "mark this" or "mark it", other words in the same sentence still save a plain mark; any other words in a later sentence are not part of the mark.

| Word | Label on the mark | Intent label |
| --- | --- | --- |
| `sitting` | sitting | SITTING_IDLE |
| `resting` | resting | SITTING_IDLE |
| `relaxing` | relaxing | SITTING_IDLE |
| `sleeping` | sleeping | SITTING_IDLE |
| `working` | working | COMPUTER_WORK |
| `studying` | studying | COMPUTER_WORK |
| `reading` | reading | COMPUTER_WORK |
| `writing` | writing | COMPUTER_WORK |
| `computer work` | computer work | COMPUTER_WORK |
| `meeting` | meeting | OTHER |
| `talking` | talking | OTHER |
| `conversation` | conversation | OTHER |
| `eating` | eating | OTHER |
| `cooking` | cooking | OTHER |
| `shopping` | shopping | OTHER |
| `watching tv` | watching tv | OTHER |
| `gaming` | gaming | OTHER |
| `cleaning` | cleaning | OTHER |
| `driving` | driving | DRIVING |
| `passenger` | passenger | VEHICLE_PASSENGER |
| `commuting` | commuting | VEHICLE_PASSENGER |
| `travelling` | travelling | VEHICLE_PASSENGER |
| `riding` | riding | OTHER |
| `flying` | flying | FLIGHT_CABIN |
| `walking` | walking | WALKING |
| `exercising` | exercising | WORKOUT |
| `workout` | workout | WORKOUT |
| `running` | running | WORKOUT |
| `cycling` | cycling | WORKOUT |
| `other` | other | OTHER |

#### Small words allowed between a command and its intent word
- `also`
- `am`
- `and`
- `are`
- `as`
- `currently`
- `i`
- `i'm`
- `im`
- `just`
- `please`
- `we`
- `we're`
- `were`

#### Negations (the mark is saved plain, with no intent)
- `aren't`
- `arent`
- `can't`
- `cant`
- `don't`
- `dont`
- `isn't`
- `isnt`
- `never`
- `no`
- `not`
- `nothing`
- `wasn't`
- `wasnt`
- `won't`
- `wont`

#### Question words (a sentence starting with one is never a command)
- `am`
- `are`
- `can`
- `could`
- `did`
- `do`
- `does`
- `how`
- `is`
- `may`
- `shall`
- `should`
- `was`
- `were`
- `what`
- `when`
- `where`
- `which`
- `who`
- `whom`
- `whose`
- `why`
- `will`
- `would`

#### Exceptions to the question rule
- `can you`
- `could you`
<!-- voice-marks:end -->

## What does not work

- Hindi and other languages are not supported yet. English only for now.
- The word "forget" is never a mark command. It belongs to the Forget feature and is deliberately left out here.
- There is no hotword and no always-on listening.
- Questions phrased as commands are ignored. A sentence that starts with a question word is never a command, so "what did you mark" is not a mark; it is answered as a normal question. The only exceptions are "can you" and "could you" followed by a command, for example "can you mark that".
- If you say an intent after a negation ("mark that, I'm not driving"), the mark is saved plain, with no intent.

## Privacy

- Audio is never stored or uploaded.
- Recognition runs on the phone's on-device recogniser only.
- Only the time, source and label are kept.

## Turning it off

Voice marks are always on in this build. There is no setting to turn them off yet; a switch is planned for a later build. Voice marks only happen after you press push-to-talk (or after a tap, on the voice path).

## For developers: keeping this page in sync

The block between the markers is generated by `MarkPhrasesDoc.render()` from `MarkPhrases` (the parser's data table). `VoiceMarksDocTest` fails if a phrase in the table is missing from this page or the page lists a phrase that is not in the table. After changing the table, regenerate with:

`VOICE_MARKS_REGEN=1 ./gradlew --no-daemon :services:voice:testDebugUnitTest --tests '*VoiceMarksDocTest'` (from `android-companion`)

The docs, the README and `VOICE_MARKS_REGEN` are declared test inputs, so the test re-runs after a hand edit or a regen. Then run the command again without the variable and commit the result. The README list is generated the same way from `VoiceCommandGroup.examples`.
