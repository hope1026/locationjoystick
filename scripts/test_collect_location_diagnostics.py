#!/usr/bin/env python3
"""Run: python3 scripts/test_collect_location_diagnostics.py (no device needed)."""

import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import Mock, patch


SPEC = importlib.util.spec_from_file_location(
    "location_diagnostics", Path(__file__).with_name("collect-location-diagnostics.py"),
)
diagnostics = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(diagnostics)


class DiagnosticsTest(unittest.TestCase):
    def test_no_device_and_unauthorized_fail_clearly(self):
        for inventory, expected in [
            ("List of devices attached\n", "states: none"),
            ("List of devices attached\nphone\tunauthorized\n", "unauthorized"),
            ("phone\toffline\n", "offline"),
        ]:
            with self.subTest(inventory=inventory), self.assertRaisesRegex(diagnostics.DiagnosticError, expected):
                diagnostics.select_device(inventory)

    def test_multiple_online_devices_require_serial(self):
        with self.assertRaisesRegex(diagnostics.DiagnosticError, "Multiple"):
            diagnostics.select_device("first\tdevice\nsecond\tdevice\n")
        self.assertEqual("second", diagnostics.select_device("first\tdevice\nsecond\tdevice\n", "second"))

    def test_explicit_unauthorized_or_missing_serial_is_rejected(self):
        for serial in ("bad", "missing"):
            with self.subTest(serial=serial), self.assertRaisesRegex(diagnostics.DiagnosticError, "not online"):
                diagnostics.select_device("good\tdevice\nbad\tunauthorized\n", serial)

    def test_single_online_device_ignores_offline_other_device(self):
        self.assertEqual("good", diagnostics.select_device("bad\toffline\ngood\tdevice model:Note8\n"))

    def test_failed_device_gate_retains_inventory_and_manifest(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(diagnostics.subprocess, "run") as run:
            run.return_value = subprocess.CompletedProcess([], 0, "phone\tunauthorized\n", "")
            collector = diagnostics.Collector(folder)
            with patch.object(diagnostics.subprocess, "Popen") as popen:
                self.assertEqual(1, collector.run())
                popen.assert_not_called()
            manifest = json.loads((Path(folder) / "manifest.json").read_text())
            self.assertEqual("failed", manifest["status"])
            self.assertEqual(1, len(manifest["commands"]))
            self.assertEqual(1, manifest["exit_status"])

    def test_timeout_keeps_partial_output_and_index(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(diagnostics.subprocess, "run") as run:
            run.side_effect = subprocess.TimeoutExpired(["adb"], 1, output=b"partial", stderr=b"unavailable")
            collector = diagnostics.Collector(folder, serial="phone", command_timeout=1)
            record, stdout = collector.command("location", ["shell", "dumpsys", "location"])
            self.assertTrue(record["timed_out"])
            self.assertIsNone(record["exit_status"])
            self.assertEqual("partial", stdout)
            self.assertEqual("unavailable", (Path(folder) / record["stderr_file"]).read_text())
            self.assertGreaterEqual(record["ended_elapsed_seconds"], record["started_elapsed_seconds"])
            self.assertEqual(1, run.call_args.kwargs["timeout"])

    def test_missing_adb_is_recorded(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(diagnostics.subprocess, "run", side_effect=FileNotFoundError("adb missing")):
            collector = diagnostics.Collector(folder)
            self.assertEqual(1, collector.run())
            self.assertIn("adb missing", collector.commands[0]["error"])

    def run_fake_collection(self, folder, snapshot_effect=None, terminate_timeout=False):
        process = Mock()
        process.poll.return_value = None
        process.returncode = -15
        if terminate_timeout:
            process.wait.side_effect = [subprocess.TimeoutExpired("adb logcat", 5), -9]
        def command_result(command, **kwargs):
            if command[1:] == ["devices", "-l"]:
                return subprocess.CompletedProcess(command, 0, "phone\tdevice\n", "")
            return subprocess.CompletedProcess(command, 1, "null\n", "field unavailable\n")
        collector = diagnostics.Collector(folder, duration=0.01)
        with patch.object(diagnostics.subprocess, "run", side_effect=command_result), \
                patch.object(diagnostics.subprocess, "Popen", return_value=process), \
                patch.object(collector, "snapshot_loop", side_effect=snapshot_effect):
            result = collector.run()
        return collector, process, result

    def test_complete_collection_terminates_logcat_and_keeps_unavailable_fields(self):
        with tempfile.TemporaryDirectory() as folder:
            collector, process, result = self.run_fake_collection(folder)
            self.assertEqual(0, result)
            process.terminate.assert_called_once()
            process.wait.assert_called_once_with(timeout=5)
            self.assertTrue(all(handle.closed for handle in collector.logcat_files))
            self.assertEqual(-15, collector.manifest["logcat"]["exit_status"])
            self.assertEqual(len(diagnostics.METADATA_COMMANDS), collector.manifest["commands_with_errors"])

    def test_interrupt_terminates_logcat_and_retains_partial_evidence(self):
        with tempfile.TemporaryDirectory() as folder:
            collector, process, result = self.run_fake_collection(folder, KeyboardInterrupt())
            self.assertEqual(130, result)
            self.assertEqual("interrupted", collector.manifest["status"])
            process.terminate.assert_called_once()
            self.assertTrue((Path(folder) / "manifest.json").is_file())

    def test_collection_failure_stops_logcat(self):
        with tempfile.TemporaryDirectory() as folder:
            collector, process, result = self.run_fake_collection(folder, diagnostics.DiagnosticError("device disconnected"))
            self.assertEqual(1, result)
            process.terminate.assert_called_once()
            self.assertEqual("failed", collector.manifest["status"])

    def test_unexpected_failure_stops_logcat_and_reports_failure(self):
        with tempfile.TemporaryDirectory() as folder:
            collector, process, result = self.run_fake_collection(folder, RuntimeError("unexpected"))
            self.assertEqual(1, result)
            process.terminate.assert_called_once()
            self.assertEqual("failed", collector.manifest["status"])
            self.assertIn("RuntimeError", collector.manifest["error"])

    def test_unresponsive_logcat_is_killed_after_bounded_wait(self):
        with tempfile.TemporaryDirectory() as folder:
            collector, process, result = self.run_fake_collection(folder, terminate_timeout=True)
            self.assertEqual(0, result)
            process.terminate.assert_called_once()
            process.kill.assert_called_once()
            self.assertEqual(2, process.wait.call_count)

    def test_snapshot_loop_is_bounded_and_records_location_services_and_power(self):
        process = Mock()
        process.poll.return_value = None
        with tempfile.TemporaryDirectory() as folder:
            collector = diagnostics.Collector(folder, duration=0.01, interval=2, serial="phone")
            collector.logcat = process
            with patch.object(diagnostics.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "evidence", "")) as run:
                collector.snapshot_loop()
            self.assertEqual(["location", "services", "power"], [record["label"] for record in collector.commands])
            self.assertTrue(all(call.kwargs["timeout"] <= 0.01 for call in run.call_args_list))
            self.assertIn("capture_duration_seconds", collector.manifest)

    def test_nonpositive_or_nonfinite_times_rejected(self):
        for value in ("0", "-1", "nan", "inf"):
            with self.subTest(value=value), self.assertRaises(Exception):
                diagnostics.positive_number(value)


if __name__ == "__main__":
    unittest.main()
