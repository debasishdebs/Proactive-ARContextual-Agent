"""Job output export and workspace lifecycle (core plan 4.2; Python path only). One job, one container, one export.

    create        journal {job, stage dir, export path, volume pca-job-<id>} BEFORE the volume exists, create the volume, stage the inputs read-only
    (container)   runs with the volume at /out and exits; the volume survives the exit
    export        disk check (`disk_low`), `podman volume export` through a capped pipe (cap hit = `too_large`), tar written under the job's stage dir,
                  collected (collector.py: regular files only, bounds from headers), then THE VOLUME IS REMOVED at once
    abandon       failure, cancel, void: volume and transient files removed without exporting
Every other copy (inputs, anything left behind after a crash) is removed by the journal-driven cleanup (journal.CleanupExecutor), at every
terminal outcome and by the orphan sweep at helper start. CORE retains no artifact.
"""
import os

from . import collector
from .runner import volume_name
from .runtime import ExportTooLarge, RuntimeFail

TAR_ENTRY_ALLOWANCE = 1024  # per member: 512 header + up to 511 padding
TAR_TRAILER = 10240


class WorkspaceFail(Exception):
    """`code` is a protocol fail code (disk_low, too_large, bad_output, runtime_error)."""

    def __init__(self, code):
        super().__init__(code)
        self.code = code


def export_cap_bytes(profile):
    return profile.out_max_bytes + 2 * profile.out_max_files * TAR_ENTRY_ALLOWANCE + TAR_TRAILER


class Workspace:
    def __init__(self, runtime, store, journal, profile, probes, host, log):
        self.runtime, self.store, self.journal, self.profile = runtime, store, journal, profile
        self.probes, self.host, self.log = probes, host, log

    # ------------------------------------------------------------------ paths
    def stage(self, job_id):
        return self.store.job_stage_dir(job_id)

    def export_path(self, job_id):
        return self.stage(job_id) / "export.tar"

    def extract_dir(self, job_id):
        return self.stage(job_id) / "extract"

    # ------------------------------------------------------------------ 1. create
    def create(self, job_id, payload_bytes):
        name = volume_name(job_id)
        stage = self.stage(job_id)
        if job_id not in self.journal.known_job_ids():
            self.journal.add_job(job_id, name, str(stage), str(self.export_path(job_id)))
        os.makedirs(str(stage), mode=0o700, exist_ok=True)
        try:
            self.runtime.create_volume(job_id, name)  # journals the volume name first, then creates it
        except RuntimeFail:
            raise WorkspaceFail("runtime_error")
        indir = stage / "in"
        os.makedirs(str(indir), mode=0o700, exist_ok=True)
        fd = os.open(str(indir / "payload.json"), os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
        try:
            os.write(fd, payload_bytes)
        finally:
            os.close(fd)
        return indir

    # ------------------------------------------------------------------ 3. export (bounded) + collect
    def export_and_collect(self, job_id, cancel=None):
        """Call only after the container has exited. Returns {relative name: bytes}. The volume is removed before this returns, whatever happens."""
        name = volume_name(job_id)
        cap = export_cap_bytes(self.profile)
        try:
            if self.probes.free_disk_bytes(str(self.store.stage_dir)) < cap:
                raise WorkspaceFail("disk_low")
            try:
                self.runtime.export_volume(name, self.export_path(job_id), cap, cancel)
            except ExportTooLarge:
                raise WorkspaceFail("too_large")
            except RuntimeFail as e:
                raise WorkspaceFail("killed" if e.code == "killed" else "runtime_error")
            except OSError:
                raise WorkspaceFail("runtime_error")
            try:
                return collector.collect(self.export_path(job_id), collector.limits_of(self.profile), dest=self.extract_dir(job_id), cancel=cancel)
            except collector.CollectorRefused as e:
                self.log.event("collector_refused", code=e.code)
                raise WorkspaceFail(e.code)
        finally:
            self.runtime.remove_volume(name)

    # ------------------------------------------------------------------ 5. retention helpers
    def remove_transient(self, job_id):
        """export.tar and the extraction tree (the data lives in memory by now)."""
        ok = self.store.remove_path(self.export_path(job_id))
        return self.store.remove_path(self.extract_dir(job_id)) and ok

    def abandon(self, job_id):
        """No export: failure, cancel or void. True when the volume and the transient files are gone."""
        ok = self.runtime.remove_volume(volume_name(job_id))
        self.remove_transient(job_id)
        return self.store.remove_path(self.stage(job_id)) and ok  # the whole stage dir, also one a late writer recreated
