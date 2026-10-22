# Codex final pass 11-14 (glasses)
Status: DONE. Merged s3a/int (d1689a4e, already up to date).
Commits: ee0bde77 (11), 77eb84a7 (12+13+14 code, tests for 11-14).
Tests (clean): core:capture 349, glasses 748, services:glasses-link 276; 0 failures.
Mutation checks: each fix reverted separately, its test failed (11, 12, 13, 14).
Rulings: 11 active getter checks the TRIAL deadline on the elapsed clock (primitive volatile, no lock/alloc); revert recorded at next tick/statusAck.
12 revertIf reverts A alone when staged B owns the receiver; B stays pending and activates.
13 pending identity is PlanId(install, gen, mode).
14 stamp replaced by the first message after PlanController.onLinkSession() (called from the statusAck hook at session open); later differing sessions in the same link session are ignored.
