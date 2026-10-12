# Phone Processing Policy

## Upload + Processing Schedule
1. If battery is `> 30%`:
- Send/process frames every `15 minutes`.

2. If battery is `<= 30%`:
- Send/process only when charging.

## Local Model Guardrail
- Local model inference is allowed only when:
  - Device is charging, OR
  - Battery is `> 70%`.

## Suggested Runtime Checks
- Evaluate policy at:
  - Batch tick.
  - Battery change.
  - Charging state change.
  - User manual query.

## Manual Override
- Explicit user query (`Hi Rokid ...`) can bypass schedule delay for response generation.
