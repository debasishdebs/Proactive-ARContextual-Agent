"""Battery + charging policy for phone-side frame processing."""

from dataclasses import dataclass


@dataclass
class PhoneState:
    battery_percent: int
    is_charging: bool


@dataclass
class ProcessingPolicy:
    sync_interval_minutes: int
    can_process_now: bool
    can_run_local_model: bool
    reason: str


def evaluate_policy(state: PhoneState) -> ProcessingPolicy:
    if state.battery_percent > 30:
        can_process_now = True
        sync_interval = 15
        reason = "battery_above_30"
    else:
        can_process_now = state.is_charging
        sync_interval = 15
        reason = "below_or_equal_30_requires_charging"

    can_run_local_model = state.is_charging or state.battery_percent > 70

    return ProcessingPolicy(
        sync_interval_minutes=sync_interval,
        can_process_now=can_process_now,
        can_run_local_model=can_run_local_model,
        reason=reason,
    )
