"""Retention of any retained artifact: expiresAt = createdAt + helperArtifactRetentionDays (7 days, from the shared constants), persisted in the
journal (wall clock: the metadata outlives the process), removed at startup, by the periodic sweep and earlier by a cleanup command.
CORE retains no artifact today; v2 APKs would be the first, and WP11 tests this with a synthetic one."""
from .protocol import constants
from .store import Store


class ArtifactExpiry:
    def __init__(self, journal, clock, log):
        self.journal, self.clock, self.log = journal, clock, log
        self.retention_ms = constants()["helperArtifactRetentionDays"] * 24 * 3600 * 1000

    def register(self, art_id, path, job_id=None):
        now = self.clock.wall_ms()
        self.journal.add_artifact(art_id, str(path), job_id, now, now + self.retention_ms)

    def sweep(self):
        """Remove every artifact whose expiry has passed; returns how many were removed. A failed removal stays registered and is retried."""
        now = self.clock.wall_ms()
        removed = 0
        for a in self.journal.artifacts():
            if (a["expiresAtMs"] <= now or now < a["createdAtMs"]) and Store.remove_path(a["path"]):  # clock set back past creation: expire
                self.journal.artifact_removed(a["id"])
                removed += 1
        if removed:
            self.log.event("expiry_sweep", removed=removed)
        return removed
