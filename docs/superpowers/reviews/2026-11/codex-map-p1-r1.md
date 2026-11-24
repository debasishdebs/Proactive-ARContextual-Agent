VERDICT: NOT READY

Paths below are relative to `android-companion/phone/src/main/java/com/rokid/context/phone/ui/settings/mapzones/`.

1. [P1] MapGeometry.kt:50 - At 60° latitude, a 200 km circle’s displayed southern edge extends about 3.5 km beyond its saved pause box; outward rounding cannot recover that protection - Derive conservative bounds from the rendered circle’s projection and test maximum-radius/polar containment.
2. [P2] MapCanvas.kt:186 - Ordinary pan/pinch events wait inside the long-press detector; short drags become taps and sustained movement starts drawing - Arbitrate touch slop, multitouch and long press together before choosing a gesture.
3. [P2] MapZonesScreen.kt:256 - Back remains enabled during upsert; a refusal can arrive after leaving the sheet, hiding its error, while a successful response can discard a newer draft - Block draft navigation during saving and associate completion with the submitted draft.
4. [P2] MapZonesScreen.kt:255 - If saved zones become unreadable after entering the Pause sheet, Save remains enabled because it checks only `saving` - Pass protected state into the sheet and disable Pause saving whenever unreadable.
5. [P2] MapCanvas.kt:161 - TalkBack receives only a summary; there are no shape/draft actions or coordinate fields, preventing accessible shape placement and adjustment - Implement the planned custom actions and editable coordinate fields.
6. [P2] MapZonesScreen.kt:317 - Selected seed areas offer only Seed again/Remove; P1’s edit/rename, per-cell progress and Show cells remain absent - Implement these planned P1 capabilities before declaring the phase ready.

Flag OFF retains the shipped route behavior; the existing upsert enforcement, delete confirmation, seed admission/caps and fenced tile source are reused. No new logging or network classes found. Static review only; no files edited or prohibited commands run.