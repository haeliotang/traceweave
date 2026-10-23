"""Transactional hot frames, ordered warm samples and cold transitions."""

import gzip
import hashlib
import json
import math
import os
from pathlib import Path
import sqlite3

from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.evaluation import timestamp
from traceform.representation.provenance import content_digest


class TieredStorage:
    def __init__(self, path):
        Path(path).parent.mkdir(parents=True, exist_ok=True)
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS hot (id TEXT PRIMARY KEY, ts REAL, raw TEXT, frame TEXT);
            CREATE INDEX IF NOT EXISTS hot_time ON hot(ts);
            CREATE TABLE IF NOT EXISTS warm (bucket INTEGER, model TEXT, token TEXT,
                frames INTEGER, energy_sum REAL, PRIMARY KEY(bucket,model,token));
            CREATE TABLE IF NOT EXISTS warm_trajectory (bucket INTEGER PRIMARY KEY, ts REAL, frame TEXT, frames INTEGER);
            CREATE INDEX IF NOT EXISTS warm_trajectory_time ON warm_trajectory(ts);
            CREATE TABLE IF NOT EXISTS cold (model TEXT, source TEXT, target TEXT,
                transitions INTEGER, PRIMARY KEY(model,source,target));
            CREATE TABLE IF NOT EXISTS cold_namespaces (namespace TEXT PRIMARY KEY, normalizer TEXT);
            CREATE TABLE IF NOT EXISTS cold_states (namespace TEXT, token TEXT, PRIMARY KEY(namespace,token));
            CREATE TABLE IF NOT EXISTS cold_potentials (namespace TEXT PRIMARY KEY, payload TEXT);
            CREATE TABLE IF NOT EXISTS metadata (key TEXT PRIMARY KEY, value TEXT);
        """)

        if 'normalizer' not in {row[1] for row in self.db.execute("PRAGMA table_info(cold_namespaces)")}:
            self.db.execute("ALTER TABLE cold_namespaces ADD COLUMN normalizer TEXT")
        try:
            with self.db:
                self.db.execute("INSERT OR IGNORE INTO cold_namespaces SELECT DISTINCT model,NULL FROM cold")
                self.db.execute("INSERT OR IGNORE INTO cold_states SELECT model,source FROM cold UNION SELECT model,target FROM cold")
                if self.db.execute("SELECT namespace FROM cold_namespaces WHERE length(namespace) NOT BETWEEN 1 AND 64").fetchone():
                    raise ValueError("legacy cold namespace exceeds bounded identity length")
                if self.db.execute("SELECT token FROM cold_states WHERE length(token) NOT BETWEEN 1 AND 64").fetchone():
                    raise ValueError("legacy cold state token exceeds bounded identity length")
                if self.db.execute("SELECT COUNT(*) FROM cold_namespaces").fetchone()[0] > 4:
                    raise ValueError("cold namespace limit exceeded; explicit lineage migration required")
                if self.db.execute("SELECT namespace FROM cold_states GROUP BY namespace HAVING COUNT(*) > 16").fetchone():
                    raise ValueError("cold state limit exceeds the 16-state topology")
                if not self.db.execute("SELECT value FROM metadata WHERE key='warm_trajectory_migration'").fetchone():
                    previous = None
                    for (encoded,) in self.db.execute("SELECT frame FROM hot ORDER BY ts"):
                        frame = json.loads(encoded)
                        self._append_warm_trajectory(frame, previous, timestamp(frame).timestamp())
                        previous = frame
                    self.db.execute("INSERT INTO metadata VALUES ('warm_trajectory_migration',?)",
                                    ('retained_hot_only_no_occupancy_reconstruction',))
        except ValueError:
            self.db.close()
            raise

    def _register_cold_state(self, namespace, token, normalizer=None):
        if not isinstance(namespace, str) or not 0 < len(namespace) <= 64:
            raise ValueError("bounded cold namespace required")
        if not isinstance(token, str) or not 0 < len(token) <= 64:
            raise ValueError("bounded cold state token required")
        if normalizer is not None and (not isinstance(normalizer, str) or len(normalizer) != 64
                                       or any(character not in '0123456789abcdef' for character in normalizer)):
            raise ValueError("normalizer digest required for cold lineage binding")
        self.db.execute("INSERT OR IGNORE INTO cold_namespaces VALUES (?,?)", (namespace, normalizer))
        bound = self.db.execute("SELECT normalizer FROM cold_namespaces WHERE namespace=?", (namespace,)).fetchone()[0]
        if bound is not None and normalizer is not None and bound != normalizer:
            raise ValueError("cold lineage normalizer mismatch")
        if bound is None and normalizer is not None:
            self.db.execute("UPDATE cold_namespaces SET normalizer=? WHERE namespace=?", (normalizer, namespace))
        if self.db.execute("SELECT COUNT(*) FROM cold_namespaces").fetchone()[0] > 4:
            raise ValueError("cold namespace limit exceeded; explicit lineage migration required")
        self.db.execute("INSERT OR IGNORE INTO cold_states VALUES (?,?)", (namespace, token))
        if self.db.execute("SELECT COUNT(*) FROM cold_states WHERE namespace=?", (namespace,)).fetchone()[0] > 16:
            raise ValueError("cold state limit exceeds the 16-state topology")

    def _append_warm_trajectory(self, frame, previous, instant):
        bucket = int(instant // 30) * 30
        row = self.db.execute("SELECT frame,frames FROM warm_trajectory WHERE bucket=?", (bucket,)).fetchone()
        prior = json.loads(row[0]) if row else None
        missing = max(0, (bucket - int(timestamp(previous).timestamp() // 30) * 30) // 30 - 1) if previous else 0
        continuous = bool(previous and same_stream_segment(previous, frame)
                          and 0 < instant - timestamp(previous).timestamp() <= 300 and missing == 0)
        if prior is not None:
            continuous = continuous and prior['continuous_from_previous']
            missing = max(missing, prior['missing_buckets_before'])
        fields = ['timestamp_utc', 'state_token', 'model_version', 'model_sha256', 'normalizer_sha256',
                  'boot_id', 'continuity_epoch', 'source_mode', 'cold_state_namespace', 'energy_distance']
        representative = {key: frame[key] for key in fields if key in frame}
        representative.update(bucket_epoch_seconds=bucket, bucket_frame_count=row[1] + 1 if row else 1,
                              continuous_from_previous=continuous, missing_buckets_before=missing,
                              sample_policy='latest_observed_in_utc_30s_bucket',
                              evidence_scope='ordered_downsampled_observations_not_interpolated_trajectory')
        self.db.execute("INSERT OR REPLACE INTO warm_trajectory VALUES (?,?,?,?)",
                        (bucket, instant, json.dumps(representative, allow_nan=False), representative['bucket_frame_count']))

    def warm_trajectory_records(self):
        for (encoded,) in self.db.execute("SELECT frame FROM warm_trajectory ORDER BY bucket"):
            yield json.loads(encoded)

    def close(self):
        self.db.close()

    def append(self, raw, frame):
        instant = timestamp(frame).timestamp()
        encoded = json.dumps(frame, sort_keys=True, allow_nan=False)
        identifier = content_digest({"observation_id": frame.get("observation_id"),
                                     "timestamp_utc": frame["timestamp_utc"],
                                     "model_version": frame.get("model_version"),
                                     "source_mode": frame.get("source_mode")})
        with self.db:
            previous_row = self.db.execute("SELECT value FROM metadata WHERE key='last_frame'").fetchone()
            previous = json.loads(previous_row[0]) if previous_row else None
            if previous and instant <= timestamp(previous).timestamp():
                # Monotonic watermark survives retention; replay cannot inflate statistics.
                return False
            model = frame.get("model_version", "unversioned")
            token = frame["state_token"]
            namespace = frame.get("cold_state_namespace", model)
            self._register_cold_state(namespace, token, frame.get("normalizer_sha256"))
            inserted = self.db.execute("INSERT OR IGNORE INTO hot VALUES (?,?,?,?)",
                                       (identifier, instant, json.dumps(raw, allow_nan=False), encoded)).rowcount
            if not inserted:
                return False
            self.db.execute("""INSERT INTO warm VALUES (?,?,?,?,?) ON CONFLICT(bucket,model,token)
                DO UPDATE SET frames=frames+1, energy_sum=energy_sum+excluded.energy_sum""",
                (int(instant // 30) * 30, model, token, 1, frame.get("energy_distance", 0.0)))
            self._append_warm_trajectory(frame, previous, instant)
            if (previous and same_stream_segment(previous, frame)
                    and previous.get("cold_state_namespace", previous.get("model_version", "unversioned")) == namespace):
                gap = instant - timestamp(previous).timestamp()
                if 0 < gap <= 300:
                    self._register_cold_state(namespace, previous["state_token"], frame.get("normalizer_sha256"))
                    self.db.execute("""INSERT INTO cold VALUES (?,?,?,1) ON CONFLICT(model,source,target)
                        DO UPDATE SET transitions=transitions+1""", (namespace, previous["state_token"], token))
            self.db.execute("INSERT OR REPLACE INTO metadata VALUES ('last_frame',?)", (encoded,))
        return True

    def compact(self, now):
        if now.tzinfo is None:
            raise ValueError("timezone-aware compaction time required")
        instant = now.timestamp()
        with self.db:
            hot = self.db.execute("DELETE FROM hot WHERE ts < ?", (instant - 48 * 3600,)).rowcount
            warm = self.db.execute("DELETE FROM warm WHERE bucket < ?", (instant - 30 * 86400,)).rowcount
            trajectory = self.db.execute("DELETE FROM warm_trajectory WHERE ts < ?", (instant - 30 * 86400,)).rowcount
        self.db.execute("PRAGMA wal_checkpoint(TRUNCATE)")
        return {"hot_deleted": hot, "warm_deleted": warm, "warm_trajectory_deleted": trajectory,
                "warm_trajectory_rows": self.db.execute("SELECT COUNT(*) FROM warm_trajectory").fetchone()[0], "counts": self.counts()}

    def counts(self):
        return {table: self.db.execute(f"SELECT COUNT(*) FROM {table}").fetchone()[0]
                for table in ("hot", "warm", "cold")}

    def warm_records(self):
        for bucket, model, token, frames, energy in self.db.execute("SELECT * FROM warm ORDER BY bucket,model,token"):
            yield {"bucket_epoch_seconds": bucket, "model_version": model, "state_token": token,
                   "frame_count": frames, "mean_energy_distance": energy / frames,
                   "evidence_scope": "occupancy_summary_not_ordered_trajectory"}

    def save_cold_potential(self, namespace, potential, source_sha256):
        """Retain one source-bound drift projection per existing cold lineage."""
        bound = self.db.execute("SELECT normalizer FROM cold_namespaces WHERE namespace=?", (namespace,)).fetchone()
        provenance = potential.get("coordinate_provenance", {})
        if not bound or bound[0] is None or provenance.get("normalizer_sha256") != bound[0]:
            raise ValueError("potential requires matching registered normalizer")
        if (not isinstance(source_sha256, str) or len(source_sha256) != 64
                or any(character not in '0123456789abcdef' for character in source_sha256)):
            raise ValueError("potential source digest required")
        centers, energies = potential.get("centers", []), potential.get("relative_energy", [])
        if (potential.get("protocol") != 'traceform.graph-potential.v1'
                or not potential.get("model_version") or not 2 <= len(centers) <= 16
                or any(not isinstance(potential.get(key), (int, float))
                       or not math.isfinite(potential[key]) or potential[key] <= 0
                       for key in ('bandwidth', 'mobility'))
                or potential['bandwidth'] * potential['bandwidth'] == 0
                or not math.isfinite(2 * potential['bandwidth'] * potential['bandwidth'])
                or len(energies) != len(centers) or not 1 <= len(centers[0]) <= 128
                or any(len(center) != len(centers[0]) for center in centers)
                or any(not math.isfinite(value) for center in centers for value in center)
                or any(not math.isfinite(value) for value in energies)):
            raise ValueError("bounded finite graph potential required")
        payload = json.dumps({"potential": potential, "source_sha256": source_sha256},
                             sort_keys=True, allow_nan=False)
        if len(payload.encode()) > 100000:
            raise ValueError("potential exceeds 100000 byte lineage limit")
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO cold_potentials VALUES (?,?)", (namespace, payload))

    def export_cold_bundle(self, path, model_bundles):
        if len(model_bundles) > 4:
            raise ValueError("at most four retained model bundles required")
        state_spaces = []
        with self.db:
            self.db.execute("BEGIN")
            for namespace, normalizer in self.db.execute("SELECT namespace,normalizer FROM cold_namespaces ORDER BY namespace").fetchall():
                tokens = [row[0] for row in self.db.execute("SELECT token FROM cold_states WHERE namespace=? ORDER BY token", (namespace,))]
                edges = {(source, target): count for source, target, count in self.db.execute(
                    "SELECT source,target,transitions FROM cold WHERE model=?", (namespace,))}
                state_spaces.append({"namespace": namespace, "normalizer_sha256": normalizer, "tokens": tokens,
                    "transition_matrix": [[edges.get((source, target), 0) for target in tokens] for source in tokens]})
                potential_row = self.db.execute("SELECT payload FROM cold_potentials WHERE namespace=?", (namespace,)).fetchone()
                if potential_row:
                    state_spaces[-1]["drift_projected_potential"] = json.loads(potential_row[0])
            row = self.db.execute("SELECT value FROM metadata WHERE key='last_frame'").fetchone()
            watermark = json.loads(row[0])["timestamp_utc"] if row else None
        bundle = {"protocol": "traceform.cold-label-lineage.v1", "state_spaces": state_spaces,
                  "model_bundles": model_bundles, "watermark_utc": watermark,
                  "evidence_scope": "cumulative_label_lineage_counts_not_fixed_model_probabilities"}
        compressed = gzip.compress(json.dumps(bundle, sort_keys=True, separators=(',', ':'), allow_nan=False).encode(), mtime=0)
        if len(compressed) > 500000:
            raise ValueError("cold bundle exceeds 500000 byte acceptance limit")
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name('.' + path.name + '.tmp')
        try:
            with temporary.open('wb') as output:
                output.write(compressed)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, path)
            descriptor = os.open(path.parent, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        finally:
            temporary.unlink(missing_ok=True)
        return {"bytes": len(compressed), "sha256": hashlib.sha256(compressed).hexdigest(),
                "state_spaces": len(state_spaces), "retained_models": len(model_bundles),
                "scope": bundle["evidence_scope"]}

    def hot_records(self, limit=None):
        if limit is not None:
            return list(reversed([json.loads(row[0]) for row in self.db.execute("SELECT frame FROM hot ORDER BY ts DESC LIMIT ?", (limit,))]))
        return [json.loads(row[0]) for row in self.db.execute("SELECT frame FROM hot ORDER BY ts")]

    def recent_observations(self, limit=1200):
        return list(reversed([json.loads(row[0]) for row in self.db.execute("SELECT raw FROM hot ORDER BY ts DESC LIMIT ?", (limit,))]))

    def iter_observations(self):
        for row in self.db.execute("SELECT raw FROM hot ORDER BY ts"):
            yield json.loads(row[0])
