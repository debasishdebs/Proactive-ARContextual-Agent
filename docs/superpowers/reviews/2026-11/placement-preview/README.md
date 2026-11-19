# Place Guide / suggestion card placement preview (2026-11-19)

## What was rendered
The real `HudRoot` (Glance page + a `PlaceCardInfo` overlay, same path as the glasses) at 480x640 px, 240 dpi (240x320 dp), for CENTRE, LEFT and RIGHT:
`place-{centre,left,right}.png` (Wikipedia source), `place-nearby-left.png` ("nearby · not checked"), `suggestion-{centre,left,right}.png` (v3.1 suggestion: "Suggestion · Wikipedia · fetched 12 Nov").
Text: title, a 140-char line, source. No glasses, no emulator.

## How
Robolectric 4.13 native graphics (`@GraphicsMode(NATIVE)`, qualifiers `w240dp-h320dp-hdpi`) in `android-companion/core/hud/src/test/kotlin/com/rokid/context/core/hud/render/PlacementPreviewTest.kt`; the content view is drawn into a 480x640 bitmap and saved to `core/hud/build/placement-preview/`.
Re-run: `./gradlew :core:hud:testDebugUnitTest --tests '*PlacementPreviewTest*'`. The emulator was not needed.

## What it does NOT show
Real optics, the unmeasured field of view / waveguide visible area, brightness, see-through blending (black is transparent on the glasses), the real font (Robolectric default Roboto; the glasses may differ), or motion. Layout geometry only.

## Observations
- The CENTRE card is edge to edge: its border sits at x=0 and x=480, no margin. If the visible area is smaller than the panel (likely), the border and text start would be clipped. This is the pre-B2 layout.
- LEFT/RIGHT before (60% = 144 dp): title cut ("Chowmahal..."), body 3 lines (~5-6 words), source cut ("Wikipedia · fetche...", "Suggestion · Wiki..").
- LEFT/RIGHT after (70% = 168 dp, 24 dp margin, 12 dp side padding): the title wraps to 2 lines ("Chowmahalla / Palace" in full), the body gets 4 lines (about 12 words, still ellipsised), and the source is shortened to "Wikipedia" / "Suggestion · Wikipedia" (the fetched date is dropped on side cards only; "nearby · not checked" is kept). Title, body start and source are now readable; the full 140-char line still does not fit. The card is taller, grows upward from the same bottom anchor, and touches the countdown no more than before. CENTRE is unchanged (byte-identical).
- The card sits over the Glance page: it hides the REC/phone/battery row and leaves only the top half of the "NEXT 4:12" digits and the page dots visible below it. CENTRE does the same (overlays draw in the lower band over the page). This is the existing overlay design, not a side-card regression.
- No LEFT/RIGHT card element runs outside the 480x640 frame.
- Remaining limits: real optics, field of view, brightness and the real font are not shown (the side offsets still need tuning while worn); the CENTRE card has no margin (existing design, deliberately untouched); the card still hides the page under it.
