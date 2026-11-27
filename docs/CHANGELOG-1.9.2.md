# Changelog 1.9.2

A patch on top of 1.9.1, made after the first tests on a real phone. It is the first version where place cards by photo work from end to end. It adds no database changes: the memory database stays at version 19 and the places database at version 4 (both introduced in 1.9.1).

## Read this first

- **Reference photos can now leave the phone.** When the online vision model is asked "which place is this?", the camera photo is sent together with up to two reference photos for each nearby candidate place (at most eight in a call), plus each candidate's name and description. These reference photos are the public photos fetched for seeded places and your own pin photos. The same Data saver, roaming and Offline-only rules apply as for the camera photo. Switch off the online layer under Developer in Settings > Place Guide to stop this.
- **Tested by hand on one phone and on a computer, not on glasses.** The offline layer and the "no photo, by GPS only" switch have so far run only in unit tests.
- **The matching thresholds are example values.** They have not been calibrated on real data yet.

## What a place card needs now

A card is shown for what the camera is looking at, not for what is nearby. Three layers decide, in order:

1. **Photo similarity.** The camera photo is compared with the stored reference photos of nearby places. A clear match shows the card at once. A middling match makes that place a candidate for the next layer.
2. **Online vision model.** It receives the camera photo, the reference photos of the candidates and their names and descriptions, and decides which place it is, or none.
3. **Offline caption.** With no network, the camera photo is described on the phone and the description is compared with each candidate's name and description. A pass shows a "Maybe" card.

With no location fix there is no card. A new switch, **Also show places that have no photo, by GPS only**, is off by default. When it is on, nearby places that have no reference photo at all can still show a nearby card, marked as nearby and not confirmed.

## Photos for seeded places

- Seeding an area now also fetches one public photo for each place that has one (Wikidata image or the Wikipedia lead image, with the free-licence check on Commons). The credit, author and licence are stored with each photo and shown in the card history.
- It is one flow with one progress row per area: places first, then photos, then matching. Photos are fetched on free Wi-Fi only and the work resumes by itself after a stop.
- Matching (turning each photo into something the phone can compare) starts by itself as photos arrive. It needs no charger and pauses only when the phone is warm.
- Each area row shows its own counts, for example "Photos: 150 of 269, 119 without a photo" and "Matched 150 of 150". "Retry photos" is always available.
- The thumbnail host and request pacing follow Wikimedia's limits: at most two downloads at a time, and a "slow down" answer is waited out and the work carries on.

## Fixes

- Photo links that Commons now serves from its thumbnail host were all rejected, so nearly every place was marked as having no photo.
- A stale background job from an older build kept the matching job from ever starting.
- A "slow down" answer from the photo host threw away the whole batch and waited a long time. It is now waited out inside the run.
- The Map zones screen: the top bar no longer sits under the status bar, the map stays inside its box, the list below it can be scrolled to the end, and zooming no longer jumps the view back to the whole country.
- The "no photo, by GPS only" switch also covers a low-confidence pick of the model, and photos stored before a batch stops are matched at once.

## Known limits

- Real-glasses behaviour of the new layers is not verified.
- About a third of seeded places have no free photo; they rely on the online and offline layers.
- The photo host throttles heavy use. One measurement gave roughly 30 to 35 photos a minute; a large area can take a while.
- Wikidata coverage is uneven, and the matching thresholds are not calibrated.
