#!/usr/bin/env python3
"""Arm/collect a physical-unplug baseline in the fixed synthetic validation package.

Never targets production. prepare temporarily exempts the test package from Doze so
equal-frequency sampling can run; default restriction behavior is tested separately.
"""
import argparse
import json
from pathlib import Path
import subprocess
import re
import time

PACKAGE = "com.inceptless.traceweave.actionsvalidation"


def compare(samples, prefix):
    start = next(s for s in samples if s["phase"] == prefix + "_start")
    end = next(s for s in samples if s["phase"] == prefix + "_end")
    interval = [s for s in samples if s["phase"].startswith(prefix + "_") and start["elapsedMs"] <= s["elapsedMs"] <= end["elapsedMs"]]
    charge = None if start["chargeCounter_uAh"] is None or end["chargeCounter_uAh"] is None else start["chargeCounter_uAh"] - end["chargeCounter_uAh"]
    return {"elapsed_seconds": (end["elapsedMs"] - start["elapsedMs"]) / 1000,
            "sample_count": len(interval),
            "max_sample_gap_seconds": max((b["elapsedMs"] - a["elapsedMs"]) / 1000 for a, b in zip(interval, interval[1:])),
            "all_samples_unplugged": all(s["plugged"] == 0 for s in interval),
            "all_samples_screen_off": all(not s["interactive"] for s in interval),
            "whole_device_charge_drop_uAh": charge,
            "same_process": start["pid"] == end["pid"],
            "process_cpu_delta_ms": end["processCpuMs"] - start["processCpuMs"] if start["pid"] == end["pid"] else None}


def timing_and_dose_valid(baseline, work, submitted):
    return 9 <= submitted <= 11 and all(600 <= p["elapsed_seconds"] <= 660 and p["sample_count"] >= 10
        and p.get("max_sample_gap_seconds", float("inf")) <= 75
        for p in (baseline, work))


def controlled_comparison_valid(baseline, work, submitted, screen_on_events=0, power_connected_events=0):
    return screen_on_events == 0 and power_connected_events == 0 and timing_and_dose_valid(baseline, work, submitted) and all(
        p["all_samples_unplugged"] and p["all_samples_screen_off"] for p in (baseline, work))


def save_probe_record(output, state):
    name = "physical_background_raw.json" if state["status"] == "complete" else "incomplete_background_raw.json"
    target = output / name
    temporary = target.with_suffix(".tmp")
    temporary.write_text(json.dumps(state, indent=2) + "\n")
    temporary.replace(target)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("mode", choices=("prepare", "collect", "restore"))
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--controlled", action="store_true", help="Visible foreground controller; requires consent for temporary notification permission")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    adb = [args.adb, "-s", args.serial]

    def run(*command):
        return subprocess.check_output(adb + list(command), text=True, timeout=40)

    setup_file = args.output / "measurement_setup.json"
    if args.mode == "prepare":
        # Capture original settings before changing only the isolated package.
        whitelist = run("shell", "dumpsys", "deviceidle", "whitelist")
        original = any(len(parts := line.split(",")) > 1 and parts[1] == PACKAGE for line in whitelist.splitlines())
        setup = {"package": PACKAGE, "originally_whitelisted": original,
                 "original_bucket": run("shell", "am", "get-standby-bucket", PACKAGE).strip(),
                 "original_power_save": run("shell", "settings", "get", "global", "low_power").strip(),
                 "controlled_probe_exemption": True, "physical_usb_disconnect_required": True,
                 "no_global_batterystats_reset": True, "foreground_controller": args.controlled}
        notifications = run("shell", "dumpsys", "package", PACKAGE)
        setup["original_notification_permission"] = bool(re.search(r"android.permission.POST_NOTIFICATIONS: granted=true", notifications))
        setup_file.write_text(json.dumps(setup, indent=2) + "\n")
        if not original:
            run("shell", "dumpsys", "deviceidle", "whitelist", "+" + PACKAGE)
        try:
            if args.controlled and not setup["original_notification_permission"]:
                run("shell", "pm", "grant", PACKAGE, "android.permission.POST_NOTIFICATIONS")
            action = "ARM_CONTROLLED" if args.controlled else "ARM_BACKGROUND"
            run("shell", "am", "start", "-W", "-S", "-n", PACKAGE + "/com.inceptless.traceweave.BackgroundProbeActivity", "-a", action)
            filename = "controlled-probe.json" if args.controlled else "background-probe.json"
            deadline = time.monotonic() + 10
            state = {}
            while time.monotonic() < deadline:
                result = subprocess.run(adb + ["exec-out", "run-as", PACKAGE, "cat", "files/" + filename], capture_output=True, text=True, timeout=20)
                if result.returncode == 0:
                    state = json.loads(result.stdout); break
                time.sleep(.5)
            if state["status"] != "await_unplug":
                raise RuntimeError("Probe not armed")
            run("shell", "input", "keyevent", "3")
            run("shell", "input", "keyevent", "223")
        except Exception:
            if args.controlled:
                run("shell", "am", "force-stop", PACKAGE)
                if not setup["original_notification_permission"]:
                    run("shell", "pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
            if not original:
                run("shell", "dumpsys", "deviceidle", "whitelist", "-" + PACKAGE)
            raise
        print("ARMED. Physically unplug USB and leave the screen off. Collect after the two ten-minute phases.")
    elif args.mode == "collect":
        filename = "controlled-probe.json" if args.controlled else "background-probe.json"
        state = json.loads(run("exec-out", "run-as", PACKAGE, "cat", "files/" + filename))
        save_probe_record(args.output, state)
        if state["status"] != "complete":
            raise RuntimeError("Measurement incomplete: " + state["status"] + "; keep the raw record and do not infer a completed comparison")
        baseline, work = compare(state["samples"], "baseline"), compare(state["samples"], "work")
        result = {"package": PACKAGE, "baseline": baseline, "work": work,
                  "tasks_submitted": state["tasksSubmitted"], "tasks_completed": state["tasksCompleted"],
                  "tasks_still_active": state["tasksStillActive"], "one_ordered_trial_only": True,
                  "sampling_and_probe_cost_included": True,
                  "whole_device_energy_is_not_uid_energy": True,
                  "foreground_controller": state.get("foregroundController", False),
                  "same_wake_lock_both_phases": state.get("sameWakeLockBothPhases", False),
                  "execution_mode": state.get("executionMode", "background_job_scheduler"),
                  "no_battery_life_or_model_inference_claim": True}
        result["screen_on_events"] = state.get("screenOnEvents")
        result["power_connected_events"] = state.get("powerConnectedEvents")
        result["transition_tracking_available"] = result["screen_on_events"] is not None and result["power_connected_events"] is not None
        valid = controlled_comparison_valid(baseline, work, state["tasksSubmitted"],
            result["screen_on_events"] if result["foreground_controller"] else 0,
            result["power_connected_events"] if result["foreground_controller"] else 0)
        result["comparison_conditions_passed"] = valid
        result["physical_screen_off_conditions_passed"] = all(p["all_samples_unplugged"] and p["all_samples_screen_off"] for p in (baseline, work))
        result["timing_and_dose_conditions_passed"] = timing_and_dose_valid(baseline, work, state["tasksSubmitted"])
        result["background_deferral_observed"] = not result["timing_and_dose_conditions_passed"] and not result["foreground_controller"]
        (args.output / "physical_background_summary.json").write_text(json.dumps(result, indent=2) + "\n")
        print(json.dumps(result, indent=2))
        if not valid:
            raise SystemExit("Controlled timing/dose conditions not met; retain this as an observational background record")
    else:
        setup = json.loads(setup_file.read_text())
        if setup["package"] != PACKAGE:
            raise RuntimeError("Unexpected setup package")
        if not setup["originally_whitelisted"]:
            run("shell", "dumpsys", "deviceidle", "whitelist", "-" + PACKAGE)
        if setup.get("foreground_controller"):
            run("shell", "am", "force-stop", PACKAGE)
            if not setup["original_notification_permission"]:
                run("shell", "pm", "revoke", PACKAGE, "android.permission.POST_NOTIFICATIONS")
        print("Original test-package optimization exemption restored")


if __name__ == "__main__":
    main()
