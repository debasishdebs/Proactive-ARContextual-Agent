# Summary and Aggregate slice review

Reviewer: Codex gpt-6.1-sol (read-only, medium). Scope: diff v0.9.3..v0.9.4 slice 1 (proc.summary, agg.window, gate.compare, starter-moment-summary).
Outcome: NOT READY (1 Important: textless Scene still called the model); fixed in c314ae86, not re-reviewed

- **Important — [Executors.kt:218](/Users/debasishkanhar/Desktop/rokid/pca-v094m/android-companion/services/orchestration/src/main/java/com/rokid/context/canvas/Executors.kt:218):** `SummaryExecutor` checks the binding but never checks whether a Scene has visible text. Run `Scenes → Summary → Confidence check → Save memory` on a Scene whose summaries/captions are absent or hidden: `text: ""` still triggers a paid model call over metadata. A fabricated response with confidence `0.9` then passes Confidence check and appears as an `OK` fact in the save preview. Skip textless Scenes before calling the model.

VERDICT NOT READY