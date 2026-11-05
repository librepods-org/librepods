#!/usr/bin/env python3
"""Regression tests for Linux build and packaging metadata."""

from contextlib import contextmanager
import json
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tarfile
import tempfile
import tomllib
import unittest
import xml.etree.ElementTree as ElementTree

import yaml


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
LINUX_ROOT = REPOSITORY_ROOT / "linux-rust"
LICENSE = REPOSITORY_ROOT / "LICENSE"
WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "ci-linux-rust.yml"
FLATPAK_MANIFEST = LINUX_ROOT / "flatpak" / "me.kavishdevar.librepods.yaml"
METAINFO = LINUX_ROOT / "flatpak" / "me.kavishdevar.librepods.metainfo.xml"
FLAKE_LOCK = REPOSITORY_ROOT / "flake.lock"


def load_yaml(path: Path):
    """Load YAML without YAML 1.1 coercing the GitHub `on` key."""
    return yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


@contextmanager
def built_source_archive(version: str = "9.8.7"):
    """Run the real Just recipe with only Cargo's vendoring isolated."""
    just = os.environ.get("JUST") or shutil.which("just")
    if just is None:
        raise RuntimeError("just is required to test the source archive recipe")

    with tempfile.TemporaryDirectory() as temporary_directory:
        repository = Path(temporary_directory) / "repository"
        linux_root = repository / "linux-rust"
        linux_root.mkdir(parents=True)

        shutil.copy2(LICENSE, repository / "LICENSE")
        for filename in ("Cargo.toml", "Cargo.lock", "Justfile"):
            shutil.copy2(LINUX_ROOT / filename, linux_root / filename)
        for directory in ("assets", "flatpak", "src"):
            shutil.copytree(LINUX_ROOT / directory, linux_root / directory)

        fake_bin = Path(temporary_directory) / "bin"
        fake_bin.mkdir()
        fake_cargo = fake_bin / "cargo"
        fake_cargo.write_text(
            "#!/bin/sh\n"
            "set -eu\n"
            "test \"$1\" = vendor\n"
            "mkdir -p \"$2\"\n"
            "printf 'vendored dependency\\n' > \"$2/dependency.txt\"\n",
            encoding="utf-8",
        )
        fake_cargo.chmod(0o755)

        environment = os.environ.copy()
        environment["PATH"] = f"{fake_bin}{os.pathsep}{environment['PATH']}"
        command = [
            just,
            "--justfile",
            str(linux_root / "Justfile"),
            "tarball",
            version,
        ]
        completed_process = subprocess.run(
            command,
            cwd=linux_root,
            env=environment,
            capture_output=True,
            text=True,
        )
        if completed_process.returncode != 0:
            raise RuntimeError(
                "source archive recipe failed\n"
                f"command: {shlex.join(command)}\n"
                f"working directory: {linux_root}\n"
                f"exit status: {completed_process.returncode}\n"
                "stdout:\n"
                f"{completed_process.stdout.rstrip() or '<empty>'}\n"
                "stderr:\n"
                f"{completed_process.stderr.rstrip() or '<empty>'}"
            )

        yield linux_root / "dist" / f"librepods-v{version}-source.tar.gz"


class PackagingTests(unittest.TestCase):
    def test_nixpkgs_lock_is_newer_than_reported_stale_revision(self):
        lock = json.loads(FLAKE_LOCK.read_text(encoding="utf-8"))

        self.assertNotEqual(
            "5d6bdbddb4695a62f0d00a3620b37a15275a5093",
            lock["nodes"]["nixpkgs"]["locked"]["rev"],
            "flake.lock must advance beyond the stale revision reported in #550",
        )

    def test_treefmt_uses_root_nixpkgs_input(self):
        lock = json.loads(FLAKE_LOCK.read_text(encoding="utf-8"))
        nodes = lock["nodes"]
        root_inputs = nodes[lock["root"]]["inputs"]
        treefmt = nodes[root_inputs["treefmt-nix"]]

        self.assertEqual(
            ["nixpkgs"],
            treefmt["inputs"]["nixpkgs"],
            "treefmt-nix must follow the root nixpkgs input",
        )

        nixpkgs_nodes = [
            name
            for name, node in nodes.items()
            if node.get("original", {}).get("repo", "").lower() == "nixpkgs"
        ]
        self.assertEqual(
            ["nixpkgs"],
            nixpkgs_nodes,
            "the lock graph must contain one nixpkgs checkout",
        )

    def test_linux_workflow_runs_for_fix_linux_bugs(self):
        workflow = load_yaml(WORKFLOW)
        branches = workflow["on"]["push"]["branches"]

        self.assertIn(
            "fix-linux-bugs",
            branches,
            "the integration branch must exercise Linux CI",
        )

    def test_linux_workflow_watches_its_current_filename(self):
        workflow = load_yaml(WORKFLOW)
        paths = workflow["on"]["push"]["paths"]

        self.assertIn(
            ".github/workflows/ci-linux-rust.yml",
            paths,
            "changes to the Linux Rust workflow must trigger that workflow",
        )
        self.assertNotIn(".github/workflows/linux-build.yml", paths)

    def test_source_archive_preserves_project_license_bytes(self):
        version = "9.8.7"
        member_name = f"librepods-v{version}/LICENSE"
        expected_license = LICENSE.read_bytes()

        with built_source_archive(version) as archive_path:
            with tarfile.open(archive_path, "r:gz") as archive:
                members = set(archive.getnames())
                self.assertIn(member_name, members)

                archived_license = archive.extractfile(member_name)
                self.assertIsNotNone(
                    archived_license,
                    "the archived LICENSE must be a regular file",
                )
                actual_license = archived_license.read()

        self.assertEqual(
            expected_license,
            actual_license,
            "the source archive must preserve the root AGPL LICENSE byte-for-byte",
        )

    def test_flatpak_x11_fallback_has_ipc_access(self):
        manifest = load_yaml(FLATPAK_MANIFEST)
        finish_arguments = manifest["finish-args"]

        self.assertIn("--socket=fallback-x11", finish_arguments)
        self.assertIn(
            "--share=ipc",
            finish_arguments,
            "X11 fallback requires host IPC for its shared-memory extension",
        )

    def test_metainfo_declares_current_release(self):
        package = tomllib.loads(
            (LINUX_ROOT / "Cargo.toml").read_text(encoding="utf-8")
        )["package"]
        release = ElementTree.parse(METAINFO).getroot().find("./releases/release")

        self.assertIsNotNone(release, "Flathub requires AppStream release metadata")
        self.assertEqual(package["version"], release.get("version"))
        self.assertEqual("2025-11-10", release.get("date"))


if __name__ == "__main__":
    unittest.main()
