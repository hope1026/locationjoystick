#!/usr/bin/env python3
"""Collect read-only Android location evidence without changing app/device settings.

Run while reproducing the jump: python3 scripts/collect-location-diagnostics.py
Raw files can contain locations; the terminal prints only status and the output path.
"""

import argparse
import json
import math
from pathlib import Path
import subprocess
import sys
import tempfile
import time
from datetime import datetime, timezone


PACKAGE = "com.locationjoystick.app"
LOG_FILTERS = [
    "MockLocationService:V", "ReplayOrchestrator:V", "RouteReplayEngine:V",
    "TeleportRouteEngine:V", "LocationManagerService:W", "LocationManager:W",
    "GnssLocationProvider:W", "NetworkLocationProvider:W", "FusedLocationProvider:W",
    "AndroidRuntime:E", "ActivityManager:I", "*:S",
]
METADATA_COMMANDS = [
    ("device-model", ["shell", "getprop", "ro.product.model"]),
    ("android-api", ["shell", "getprop", "ro.build.version.sdk"]),
    ("android-version", ["shell", "getprop", "ro.build.version.release"]),
    ("device-time", ["shell", "date"]),
    ("device-uptime", ["shell", "cat", "/proc/uptime"]),
    ("power-state", ["shell", "dumpsys", "power"]),
    ("package", ["shell", "dumpsys", "package", PACKAGE]),
    ("appops", ["shell", "appops", "get", PACKAGE]),
    ("location-mode", ["shell", "settings", "get", "secure", "location_mode"]),
    ("location-providers", ["shell", "settings", "get", "secure", "location_providers_allowed"]),
    ("wifi-scanning", ["shell", "settings", "get", "global", "wifi_scan_always_enabled"]),
    ("bluetooth-scanning", ["shell", "settings", "get", "global", "ble_scan_always_enabled"]),
]


class DiagnosticError(Exception):
    """A collection prerequisite failed; already-collected evidence is retained."""


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def as_text(value):
    if isinstance(value, bytes):
        return value.decode("utf-8", errors="replace")
    return value or ""


def select_device(inventory, requested_serial=None):
    devices = {}
    for line in inventory.splitlines():
        fields = line.split()
        if not fields or line.startswith("List of devices") or line.startswith("*"):
            continue
        if len(fields) >= 2:
            devices[fields[0]] = fields[1]
    if requested_serial:
        state = devices.get(requested_serial)
        if state != "device":
            raise DiagnosticError(
                "Selected device is not online and authorized "
                f"(state: {state or 'not listed'}). Check the cable and accept USB debugging on the phone."
            )
        return requested_serial
    online = [serial for serial, state in devices.items() if state == "device"]
    if len(online) == 1:
        return online[0]
    if len(online) > 1:
        raise DiagnosticError("Multiple online devices found. Select one with --serial SERIAL.")
    states = ", ".join(sorted(set(devices.values()))) or "none"
    raise DiagnosticError(
        f"No online authorized device found (states: {states}). "
        "Connect the phone, enable USB debugging and accept its authorization prompt."
    )


class Collector:
    def __init__(self, output, duration=120.0, interval=2.0, serial=None, command_timeout=15.0, package=PACKAGE):
        self.output = Path(output)
        self.duration = duration
        self.interval = interval
        self.serial = serial
        self.command_timeout = command_timeout
        self.package = package
        self.started = time.monotonic()
        self.commands = []
        self.logcat = None
        self.logcat_record = None
        self.logcat_files = []
        self.manifest = {
            "schema_version": 1,
            "package": package,
            "started_utc": utc_now(),
            "requested_duration_seconds": duration,
            "requested_snapshot_interval_seconds": interval,
            "command_timeout_seconds": command_timeout,
            "read_only": True,
            "commands": self.commands,
            "status": "starting",
        }

    def elapsed(self):
        return round(time.monotonic() - self.started, 6)

    def command(self, label, args, device=True, timeout=None):
        command = ["adb"] + (["-s", self.serial] if device else []) + args
        stem = f"{len(self.commands):04d}-{label}"
        record = {
            "label": label, "command": command, "started_utc": utc_now(),
            "started_elapsed_seconds": self.elapsed(),
            "stdout_file": stem + ".stdout.txt", "stderr_file": stem + ".stderr.txt",
            "exit_status": None, "timed_out": False,
        }
        self.commands.append(record)
        stdout, stderr = "", ""
        try:
            result = subprocess.run(
                command, capture_output=True, text=True, check=False,
                timeout=timeout if timeout is not None else self.command_timeout,
            )
            stdout, stderr = result.stdout, result.stderr
            record["exit_status"] = result.returncode
        except subprocess.TimeoutExpired as error:
            stdout, stderr = as_text(error.stdout), as_text(error.stderr)
            record["timed_out"] = True
            record["error"] = "Command timed out"
        except OSError as error:
            record["error"] = str(error)
        except KeyboardInterrupt:
            record["error"] = "Interrupted"
            raise
        finally:
            record["ended_utc"] = utc_now()
            record["ended_elapsed_seconds"] = self.elapsed()
            (self.output / record["stdout_file"]).write_text(as_text(stdout), encoding="utf-8")
            (self.output / record["stderr_file"]).write_text(as_text(stderr), encoding="utf-8")
        return record, as_text(stdout)

    def start_logcat(self):
        command = ["adb", "-s", self.serial, "logcat", "-v", "threadtime", "-T", "1"] + LOG_FILTERS
        self.logcat_record = {
            "command": command, "started_utc": utc_now(),
            "started_elapsed_seconds": self.elapsed(),
            "stdout_file": "logcat.stdout.txt", "stderr_file": "logcat.stderr.txt",
            "exit_status": None,
        }
        self.manifest["logcat"] = self.logcat_record
        for name in ("logcat.stdout.txt", "logcat.stderr.txt"):
            self.logcat_files.append((self.output / name).open("w", encoding="utf-8"))
        self.logcat = subprocess.Popen(command, stdout=self.logcat_files[0], stderr=self.logcat_files[1])

    def stop_logcat(self):
        try:
            if self.logcat is not None:
                if self.logcat.poll() is None:
                    self.logcat.terminate()
                    try:
                        self.logcat.wait(timeout=5)
                    except subprocess.TimeoutExpired:
                        self.logcat.kill()
                        self.logcat.wait(timeout=5)
                self.logcat_record["exit_status"] = self.logcat.returncode
        except (OSError, subprocess.TimeoutExpired) as error:
            if self.logcat_record is not None:
                self.logcat_record["cleanup_error"] = str(error)
            raise DiagnosticError("Could not confirm logcat shutdown; see manifest.json.") from error
        finally:
            if self.logcat_record is not None:
                self.logcat_record["ended_utc"] = utc_now()
                self.logcat_record["ended_elapsed_seconds"] = self.elapsed()
            for handle in self.logcat_files:
                handle.close()

    def snapshot_loop(self):
        start = time.monotonic()
        deadline = start + self.duration
        next_snapshot = start
        self.manifest["capture_started_utc"] = utc_now()
        while time.monotonic() < deadline:
            if self.logcat.poll() is not None:
                raise DiagnosticError("Live logcat stopped early; see logcat.stderr.txt.")
            now = time.monotonic()
            if now < next_snapshot:
                time.sleep(min(next_snapshot - now, deadline - now))
                continue
            for label, args in [
                ("location", ["shell", "dumpsys", "location"]),
                ("services", ["shell", "dumpsys", "activity", "services", self.package]),
                ("power", ["shell", "dumpsys", "power"]),
            ]:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    break
                self.command(label, args, timeout=min(self.command_timeout, remaining))
            next_snapshot = max(next_snapshot + self.interval, time.monotonic())
        self.manifest["capture_duration_seconds"] = round(time.monotonic() - start, 6)

    def run(self):
        exit_status = 0
        try:
            record, inventory = self.command("devices", ["devices", "-l"], device=False)
            if record["exit_status"] != 0:
                raise DiagnosticError("Cannot query adb devices. Check adb is installed; see devices stderr file.")
            self.serial = select_device(inventory, self.serial)
            self.manifest["serial"] = self.serial
            self.manifest["status"] = "collecting"
            for label, args in METADATA_COMMANDS:
                self.command(label, [self.package if arg == PACKAGE else arg for arg in args])
            self.start_logcat()
            self.snapshot_loop()
            self.manifest["status"] = "complete"
        except KeyboardInterrupt:
            exit_status = 130
            self.manifest["status"] = "interrupted"
            self.manifest["error"] = "Interrupted; partial evidence retained."
        except (DiagnosticError, OSError) as error:
            exit_status = 1
            self.manifest["status"] = "failed"
            self.manifest["error"] = str(error)
        except Exception as error:
            exit_status = 1
            self.manifest["status"] = "failed"
            self.manifest["error"] = f"Unexpected collector failure: {type(error).__name__}."
        finally:
            try:
                self.stop_logcat()
            except DiagnosticError as error:
                exit_status = 1
                self.manifest["status"] = "failed"
                self.manifest["cleanup_error"] = str(error)
            self.manifest["commands_with_errors"] = sum(
                record["exit_status"] != 0 or "error" in record for record in self.commands
            )
            self.manifest["ended_utc"] = utc_now()
            if "capture_started_utc" in self.manifest:
                self.manifest["capture_ended_utc"] = self.manifest["ended_utc"]
            self.manifest["elapsed_seconds"] = self.elapsed()
            self.manifest["exit_status"] = exit_status
            (self.output / "manifest.json").write_text(
                json.dumps(self.manifest, indent=2) + "\n", encoding="utf-8",
            )
        return exit_status


def positive_number(value):
    number = float(value)
    if not math.isfinite(number) or number <= 0:
        raise argparse.ArgumentTypeError("must be a finite number greater than zero")
    return number


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="Select an online authorized adb device")
    parser.add_argument("--package", default=PACKAGE, help="App package (use com.locationjoystick.app.fork for the debug fork)")
    parser.add_argument("--duration", type=positive_number, default=120.0, help="Capture seconds (default: 120)")
    parser.add_argument("--interval", type=positive_number, default=2.0, help="Snapshot seconds (default: 2)")
    parser.add_argument("--command-timeout", type=positive_number, default=15.0, help="Command timeout seconds (default: 15)")
    parser.add_argument("--output", type=Path, help="New output directory (default: a system temporary directory)")
    args = parser.parse_args(argv)
    try:
        if args.output is None:
            output = Path(tempfile.mkdtemp(prefix="locationjoystick-diagnostics-"))
        else:
            output = args.output.expanduser().resolve()
            output.mkdir(mode=0o700, parents=True, exist_ok=False)
    except OSError as error:
        parser.error(f"Cannot create output directory: {error}")
    collector = Collector(output, args.duration, args.interval, args.serial, args.command_timeout, args.package)
    print(f"Checking device and collecting evidence: {output}", flush=True)
    result = collector.run()
    print(f"Collection {collector.manifest['status']}. Evidence: {output}")
    if "error" in collector.manifest:
        print(collector.manifest["error"], file=sys.stderr)
    return result


if __name__ == "__main__":
    sys.exit(main())
