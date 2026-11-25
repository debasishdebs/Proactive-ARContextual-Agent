# Owner decisions 26 Nov 2026 (embeddings, names, canvas)

Embeddings
1. Default space: Nomic with a centroid reference. SigLIP 2 is selectable through the model catalog (resumable verified downloader). Catalog download must show the size and check free space (phone storage is about 90 percent full).
2. Accept target precision 95 percent or higher; compare 95 against 98 offline before finalising.
3. Score margin over the runner-up: experiment offline first; decide defaults from numbers (none measured yet).
4. SigLIP 2 resident memory (about 380 MB RAM) is OK; Nomic stays the default.
5. SigLIP 2 is Apache-2.0: show the licence/NOTICE in the app; verify the tokenizer checksum (integrity only).
6. Text-channel pass from phone logs after one walk: yes.
7. Second calibration pass with Camera photos: NO (not possible for months).

Place names and prompts
8. Resolved place names in AI prompts: controlled by a Settings switch; the normal gates always apply (never under Offline only, never inside a pause zone). Coordinates never.
9. Resolved place names in app logs: yes allowed (Detailed log, local). Coordinates never.

Canvas
10. Location sensor node feeding the context hub node (glasses, watch, phone sensors merged): wanted. Reduced outputs only toward remote, export, logs.
11. Presets default to the centroid reference, but every reference mode stays selectable per node.
12. Thresholds ARE editable from the canvas: a Test run can enter a new threshold and Save writes the live setting (Developer mode, confirm, reset-to-calibrated-default available).
13. place.match: clarified; see the conversation. Live coords -> candidates -> match flow is real in the app.
14. model.vision is the VLM description node; vision.describe is the same idea under the new name.
15-21: re-asked in plain English.

Dump API: gate is Developer mode plus pairing (fingerprint, code, paired host); vectors optional (--vectors); location coordinates and resolved names included.
