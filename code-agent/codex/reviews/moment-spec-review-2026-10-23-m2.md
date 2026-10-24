1. **Critical — Late source admission can still bypass Forget** (§§8.1–8.5, lines 175–183, 222). The text fences derived writes and builders, but does not require frame admission to check forgotten seconds. Current `FrameIntake.commit:19–29` inserts without a range check; a durable receipt can also precede its frame row. Discovery can therefore miss a frame that subsequently becomes readable.
   
   **Exact edit:** Require phase 1 to discover durable inbox/archive receipts independently of frame rows. Require frame admission, receipt publication and frame commit to reject overlapping persisted Forget fences, with transactional rechecking and durable cleanup of rejected JPEGs/sidecars. Test Forget between receipt creation and frame commit, including restart.

2. **Important — Delete all discards unacknowledged cleanup** (§8.7, lines 233–234). Wiping `cleanup_outbox` after enqueueing device messages contradicts §8.6’s retention-until-ack contract. Enqueueing alone does not ensure delivery. The retained 3b WP4 contract explicitly keeps its cleanup table through Delete all.
   
   **Exact edit:** Replace the wipe policy with: “Unacknowledged cleanup survives Delete all until acknowledged. The wipe atomically preserves or transfers outstanding cleanup and adds its own commands before deleting source references.” Update the inventory/WipeDao assertions accordingly. Add crash-before-dispatch and disconnected-device recovery tests.

3. **Important — Unknown-time handling can still under-delete** (§7.1, lines 156–157; §7.3, line 164). Non-receipt `frame.ts` comes from arbitrary file mtime (`RokidFrameInbox:305`); `frame_meta.createdAt` records intake time. Treating mtime as receipt time with a default 120-second capture-delay bound is unsupported. For fully unmapped sources, merely flagging confirmation also leaves deletion completion undefined.
   
   **Exact edit:** State: “Unknown capture evidence creates no Moment. Receipt-time placement requires a verified receipt instant and a demonstrated delay bound; otherwise uncertainty is unbounded.” Persist source clock quality/uncertainty independently of the projection, treating missing legacy evidence conservatively. Replace the confirmation fallback with conservative inclusion, or explicitly keep Forget incomplete and block payload release until resolved.

The phase-local protection rule, trace-source dependencies, inherited root closure and per-type policies are present. Section 9 matches Option 1 and places Moments after 3b core. The provisional ranges correctly total **50–79**, or **51–81 including phase 0**.

VERDICT: NOT READY