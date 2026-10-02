# Rolling physical acquisition v2

`physical_acquisition_v2.py` and its acquisition engine are explicit forks of the
frozen v1 sensing/scheduling policy. V2 versions persistence and bounded location acquisition: observation,
transition and episode JSONL files rotate under an exclusive sidecar lock, at
hour boundaries or 64MiB, and retain recent segments. Frozen upstream source
files and historical frozen corpus files are untouched. This fork deliberately
duplicates the short acquisition policy so enabling rolling persistence does
not invalidate the v1 source tree digest. Future sensing-policy changes must
identify which version they affect.

Appends fsync data and the parent directory. Incomplete crash tails move to
a segment before the next record is written. Sequence names remain increasing
when wall-clock nanoseconds repeat or reverse. A live reader holds the same
lock while checking rotations, drains an old inode before opening successive
segments, and marks missing/partial rotated segments as discontinuities.
The manifold daemon resets temporal statistics when that marker is observed.

Physical observation/transition retention now uses each record's timestamp;
episodes use their end timestamp. At a successful append or maintenance pass,
records strictly older than the 48h cutoff are removed, including the expired
prefix of a boundary segment. Replacement is atomic and preserves file metadata;
readers reopen replaced inodes and recover past their last complete retained
line, marking a discontinuity. The boundary record itself is retained.
A 256-entry cache stores only timestamp bounds keyed by file identity/size/mtime,
not observations. Incomplete crash fragments fall back to file age.

Maintenance also checks the three raw ledgers while sampling is idle. Its
wrapper uses the explicit Termux home path: Android run-as HOME is not the
Termux files/home directory. Cleanup occurs on append and the approximately
hourly maintenance schedule, so idle expiry can be delayed until the next
maintenance run; this is not a continuous wall-clock deletion guarantee.
Frozen cutover archives are outside this cleanup scope.

The v2 runner shares the physical-layer singleton lock. Do not launch it beside
v1: durable v1 supervision intent must first be stopped, then v2 supervision
installed with its own recorded desired state and recovery checks. Cutover was executed after confirming actual v1 termination and archiving its
source ledgers. V2 has a separate durable intent and a persisted 15-minute
supervision job; only one production acquisition process holds the shared lock. The existing Debian acquisition environment provides NumPy/audio
dependencies; native Termux currently does not.

Events use the same writer with a distinct 30-day retention and 8MiB segment
limit. Maintenance expires idle active tails as well as archived segments.
Historical replay deliberately does not use a sidecar lock in source folders:
a read-only frozen corpus can be replayed without granting write access.

V2 now uses the Termux API `updates` location request, which has a 30-second
server-side timer, with a 35-second client budget. The frozen `once` path has
no corresponding timer: a reproduced no-fix case was followed by global API
reply failure. A completed update request may contain concatenated JSON objects;
the latest location is selected, with network last-known fallback. Coordinates
older than two minutes, unknown ages and invalid coordinates are unavailable.
The reported callback age and a conservative age upper bound including the
entire request duration are recorded separately. No fresh-position claim is
made when the device cannot obtain a recent position. This can extend a cycle
to roughly 40 seconds; the current sampler is not a 1Hz implementation.
