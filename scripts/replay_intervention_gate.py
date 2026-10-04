#!/usr/bin/env python3
"""Replay the daemon's intervention gate offline on a stored hot tier (read-only, aggregates only).

The live daemon proposes an intervention only when, at a newly inserted CSD_TIPPING event, the dwell
count reaches --dwell-steps and the sandbox counterfactual (clamping the current state's dwell at 10
steps, 30 steps ahead, 100 rollouts) recommends it. That needs the terminal risk count on the current
state to fall by more than 5 rollouts out of 100. This script reports, per stage:

  gate 1  how many CSD_TIPPING events fall at dwell >= threshold (needs the events file);
  gate 2  for frames at dwell >= threshold, what the same counterfactual recommends and by how much
          the current state's absorption probability changes.

Frames are replayed with the same segment rule and history window as the daemon. No rows, timestamps or
observation values are written, only counts and the counterfactual summary numbers.
"""
import argparse
import collections
import datetime
import json
from pathlib import Path
import sqlite3
import statistics
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from traceform.forecasting.continuity import same_stream_segment
from traceform.forecasting.sandbox_simulator import SandboxSimulator, ScenarioIntervention

HISTORY = 2048
MIN_SEGMENT = 20


def load_frames(path):
    connection = sqlite3.connect(Path(path).resolve().as_uri() + '?mode=ro', uri=True)
    try:
        return [json.loads(row[0]) for row in connection.execute('SELECT frame FROM hot ORDER BY ts, id')]
    finally:
        connection.close()


def segment_before(frames, index):
    recent = frames[max(0, index + 1 - HISTORY):index + 1]
    start = len(recent) - 1
    while start > 0 and same_stream_segment(recent[start - 1], recent[start]):
        start -= 1
    return recent[start:]


def counterfactual(frames, index, timezone):
    recent = segment_before(frames, index)
    if len(recent) < MIN_SEGMENT:
        return {'status': 'segment_too_short', 'segment_frames': len(recent)}
    intervals = [row['frame_interval_seconds'] for row in recent if row.get('frame_interval_seconds')]
    nominal = statistics.median(intervals) if intervals else 30.0
    simulator = SandboxSimulator(step_nominal_seconds=nominal, timezone_name=timezone).fit_from_records(recent, max_gap_seconds=300)
    frame = frames[index]
    token = frame['state_token']
    comparison = simulator.simulate_counterfactual(
        token, ScenarioIntervention(action_type='CLAMP_DWELL', max_dwell_steps=10), horizon_steps=30, num_rollouts=100,
        high_risk_states={token}, start_time_utc=datetime.datetime.fromisoformat(frame['timestamp_utc'].replace('Z', '+00:00')))
    change = round(comparison.risk_delta_map[token] * 100)
    return {'status': 'ok', 'segment_frames': len(recent), 'dwell_steps': frame['dwell_steps'], 'recommended_path': comparison.recommended_path,
            'risk_count_change_of_100': change, 'needed_below': -5, 'mean_jsd': round(comparison.mean_divergence_jsd, 4)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sqlite-file', type=Path, required=True)
    parser.add_argument('--events-file', type=Path, required=True)
    parser.add_argument('--output-file', type=Path, required=True)
    parser.add_argument('--timezone', default='Asia/Kathmandu')
    parser.add_argument('--dwell-steps', type=int, default=60)
    parser.add_argument('--sample', type=int, default=24, help='frames at dwell >= threshold to run through gate 2')
    args = parser.parse_args()

    frames = load_frames(args.sqlite_file)
    by_stamp = {frame['timestamp_utc']: index for index, frame in enumerate(frames)}
    events = []
    for line in args.events_file.read_text().splitlines():
        try:
            events.append(json.loads(line))
        except ValueError:
            continue
    tipping = [e for e in events if e.get('event_type') == 'CSD_TIPPING']
    matched = [e for e in tipping if e.get('timestamp_utc') in by_stamp]
    dwell_at_tipping = collections.Counter(min(frames[by_stamp[e['timestamp_utc']]]['dwell_steps'] // 10 * 10, args.dwell_steps)
                                           for e in matched)
    gate1_hits = [by_stamp[e['timestamp_utc']] for e in matched if frames[by_stamp[e['timestamp_utc']]]['dwell_steps'] >= args.dwell_steps]
    live = [i for i, f in enumerate(frames) if f.get('source_mode') == 'live_follow']
    long_dwell = [i for i in live if frames[i]['dwell_steps'] >= args.dwell_steps]
    step = max(1, len(long_dwell) // max(1, args.sample))
    sampled = long_dwell[::step][:args.sample]

    report = {
        'scope': 'offline_replay_of_intervention_gate_not_effect_validation',
        'frames': len(frames), 'tipping_events': len(tipping), 'tipping_events_matching_stored_frames': len(matched),
        'gate1_dwell_threshold': args.dwell_steps,
        'gate1_dwell_at_tipping_histogram': {str(k): v for k, v in sorted(dwell_at_tipping.items())},
        'gate1_events_at_or_above_threshold': len(gate1_hits),
        'gate1_replayed_exactly': [counterfactual(frames, i, args.timezone) for i in gate1_hits],
        'gate2_frames_at_or_above_threshold': len(long_dwell), 'gate2_sampled': len(sampled),
        'gate2_ignores_gate1': 'these frames were not necessarily at a CSD_TIPPING event; the question is only what the counterfactual says',
        'gate2_results': [counterfactual(frames, i, args.timezone) for i in sampled],
    }
    results = [r for r in report['gate2_results'] if r['status'] == 'ok']
    report['gate2_summary'] = {
        'ok': len(results),
        'recommended_paths': dict(collections.Counter(r['recommended_path'] for r in results)),
        'risk_count_change_of_100': {'min': min((r['risk_count_change_of_100'] for r in results), default=None),
                                     'max': max((r['risk_count_change_of_100'] for r in results), default=None)},
        'would_recommend': sum(1 for r in results if r['recommended_path'] == 'RECOMMEND_INTERVENTION')}
    args.output_file.parent.mkdir(parents=True, exist_ok=True)
    args.output_file.write_text(json.dumps(report, indent=2, sort_keys=True) + '\n')
    print(json.dumps({k: report[k] for k in ('tipping_events', 'tipping_events_matching_stored_frames', 'gate1_events_at_or_above_threshold', 'gate2_summary')}))


if __name__ == '__main__':
    main()
