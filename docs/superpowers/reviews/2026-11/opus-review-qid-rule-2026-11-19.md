# Review: ed27c5ae (per-QID 1 km rule) + aedb2d5f (khanna/allu stop list)

Reviewer: Opus 5.5, standing in for Codex gpt-6.1-sol (low effort). Read-only: git show and file reads only. No Gradle, no devices.

## ed27c5ae, PlaceCellSource.fetchCell

1. Coverage and safety: OK. The pre-pass (PlaceCellSource.kt:42-50) loops over every page before any gate: entity-missing, p31Malformed, person, kind and the lat/lon fallbacks. So pages that later gates skip still mark their QID. Missing coordinates on either side skip safely through the null checks. NaN is caught because `coordsDisagree` treats a non-finite haversine as disagreeing (line 119). Nothing in the pre-pass can throw. A page whose entity is missing gets `continue` in the pre-pass and is still counted `rejected` in the main loop, so that behaviour is unchanged.
2. Strictly tighter: OK. Before, a page was dropped when its own point disagreed. Now that page's QID is in `disagreeingQids`, so it is still dropped. Nothing that was rejected before can now be admitted. A QID is only marked when both points exist, so single-source places are untouched. VERIFIED_COORDINATES extras (line 34) require `q !in pageQids`, so they never have a page and never meet the rule. Places whose pages all agree are never marked: Golconda 326 m, Hussain Sagar 290 m, Ramoji 91 m and the rest are all under 1000 m. The only change in behaviour is the one intended: a QID with one agreeing page and one disagreeing page is now dropped instead of kept through the agreeing page.
3. Counting and logging: OK. `countedDisagree.add(q)` (line 59) counts each QID once. The check runs after the per-entity person, malformed and kind gates. Those gates are the same for every page of a QID, so the count is consistent and matches the old rule that people and non-monuments are not counted. The log line (line 99) has counts only: no titles, QIDs or coordinates.
4. Tests (PlaceFetchTest.kt:319-345):
   - `twoPagesOneQidWithOneDisagreeing...`: correct. Page 2 is about 4.8 km off. The old per-page code would keep wd:Q272153 through page 1 and log kept=1, so this test fails on the old behaviour. This is the real regression test.
   - `twoPagesOneQidBothAgreeing...`: correct. Page 2 is about 57 m off. It is a guard and passes on old and new code alike, which is expected.
   - P3, PlaceFetchTest.kt:343-345: `aSingleSourceUnaffectedPlaceIsKept` is an exact copy of the assertion already at line 306. Wikidata has no P625 here, so it does cover single-source, but it adds no coverage. A stronger version would use a Wikidata P625 together with a page that has no coordinates. Optional.
   - P3, PlaceCellSource.kt:58: this comment still describes the rule per candidate, while the operative comment is at line 42. Cosmetic.

## aedb2d5f, IndicLexicon / EnglishCommonWords

- Correct. `lookup` (IndicLexicon.kt:108-112) checks the stop list on the normalized raw token before folding. So "allu" and "Khanna" return null, while "aloo" and "khana" still hit. The stop list is not checked on folded forms, so the real keys are not blocked. "khanna" and "allu" are not exact keys and not folded keys ("khana", "alu"), so the key/stop-list test still holds. The new assertions in MultilingualAskTest and IndicLexiconTest are sound.
- P3, IndicLexicon.kt:10-13: four new KDoc lines lack the leading ` * `. It compiles because they are still inside `/** */`, but the KDoc renders unevenly. Cosmetic.

No P1. No P2.

READY
