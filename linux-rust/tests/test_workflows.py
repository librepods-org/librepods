#!/usr/bin/env python3
"""Static regression tests for security-sensitive GitHub workflows."""

from pathlib import Path
import re
import unittest

import yaml


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
NIX_UPDATE_WORKFLOW = (
    REPOSITORY_ROOT / ".github" / "workflows" / "ci-nix-flake-update.yml"
)
LINUX_WORKFLOW = REPOSITORY_ROOT / ".github" / "workflows" / "ci-linux-rust.yml"

CHECKOUT_ACTION = (
    "actions/checkout@11d5960a326750d5838078e36cf38b85af677262"  # v4
)
NIX_INSTALLER_ACTION = (
    "DeterminateSystems/nix-installer-action@"
    "3138316df39ed29be04236d7ffc686fa525866aa"  # v23
)
UPLOAD_ARTIFACT_ACTION = (
    "actions/upload-artifact@ea165f8d65b6e75b540449e92b4886f43607fa02"  # v4
)
DOWNLOAD_ARTIFACT_ACTION = (
    "actions/download-artifact@d3f86a106a0bac45b974a628896c90dbdf5c8093"  # v4
)
CREATE_PULL_REQUEST_ACTION = (
    "peter-evans/create-pull-request@"
    "5f6978faf089d4d20b00c7766989d076bb2fc7f1"  # v8.1.1
)
GITHUB_SCRIPT_ACTION = (
    "actions/github-script@3a2844b7e9c422d3c10d287c895573f7108da1b3"  # v9.0.0
)
RUST_TOOLCHAIN_ACTION = (
    "dtolnay/rust-toolchain@683b588c487a69d01f6c6ddf40f036ff682cc2c8"  # master
)
CACHE_ACTION = (
    "actions/cache@0057852bfaa89a56745cba8c7296529d2fc39830"  # v4
)
GH_RELEASE_ACTION = (
    "softprops/action-gh-release@3bb12739c298aeb8a4eeaf626c5b8d85266b0e65"  # v2
)
IMMUTABLE_ACTION_REFERENCE = re.compile(
    r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$"
)


def load_yaml(path: Path):
    """Load YAML without YAML 1.1 coercing the GitHub `on` key."""
    return yaml.load(path.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


def named_steps(job):
    """Index a workflow job's named steps."""
    return {step["name"]: step for step in job["steps"] if "name" in step}


class WorkflowTests(unittest.TestCase):
    def test_nix_updater_is_registered_on_schedule_and_dispatch(self):
        self.assertTrue(
            NIX_UPDATE_WORKFLOW.exists(),
            "the default branch must register the scheduled Nix updater",
        )
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)

        self.assertEqual([{"cron": "0 4 * * 1"}], workflow["on"]["schedule"])
        self.assertIn("workflow_dispatch", workflow["on"])

    def test_nix_updater_uses_reviewed_immutable_action_commits(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        action_references = [
            step["uses"]
            for job in workflow["jobs"].values()
            for step in job["steps"]
            if "uses" in step
        ]

        self.assertEqual(
            [
                CHECKOUT_ACTION,
                NIX_INSTALLER_ACTION,
                UPLOAD_ARTIFACT_ACTION,
                CHECKOUT_ACTION,
                DOWNLOAD_ARTIFACT_ACTION,
                CREATE_PULL_REQUEST_ACTION,
                GITHUB_SCRIPT_ACTION,
            ],
            action_references,
        )
        for action_reference in action_references:
            with self.subTest(action=action_reference):
                self.assertRegex(action_reference, IMMUTABLE_ACTION_REFERENCE)

    def test_linux_ci_nix_installer_uses_reviewed_immutable_commit(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        steps = named_steps(workflow["jobs"]["build"])

        self.assertEqual(NIX_INSTALLER_ACTION, steps["Install Nix"]["uses"])
        self.assertRegex(
            steps["Install Nix"]["uses"],
            IMMUTABLE_ACTION_REFERENCE,
        )

    def test_linux_ci_uses_reviewed_immutable_action_commits(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        action_references = [
            step["uses"]
            for job in workflow["jobs"].values()
            for step in job["steps"]
            if "uses" in step
        ]

        self.assertEqual(
            [
                CHECKOUT_ACTION,
                NIX_INSTALLER_ACTION,
                RUST_TOOLCHAIN_ACTION,
                CACHE_ACTION,
                UPLOAD_ARTIFACT_ACTION,
                UPLOAD_ARTIFACT_ACTION,
                UPLOAD_ARTIFACT_ACTION,
                DOWNLOAD_ARTIFACT_ACTION,
                DOWNLOAD_ARTIFACT_ACTION,
                DOWNLOAD_ARTIFACT_ACTION,
                GH_RELEASE_ACTION,
            ],
            action_references,
        )
        for action_reference in action_references:
            with self.subTest(action=action_reference):
                self.assertRegex(action_reference, IMMUTABLE_ACTION_REFERENCE)

    def test_linux_ci_build_has_only_read_permission(self):
        workflow = load_yaml(LINUX_WORKFLOW)

        self.assertIn("permissions", workflow)
        self.assertEqual({}, workflow["permissions"])
        self.assertEqual(
            {"contents": "read"},
            workflow["jobs"]["build"]["permissions"],
        )

    def test_linux_ci_pinned_rust_action_selects_stable_toolchain(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        install_rust = named_steps(workflow["jobs"]["build"])["Install Rust"]

        self.assertEqual(RUST_TOOLCHAIN_ACTION, install_rust["uses"])
        self.assertEqual("stable", install_rust["with"]["toolchain"])

    def test_linux_ci_checkout_never_persists_credentials(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        checkout_steps = [
            step
            for job in workflow["jobs"].values()
            for step in job["steps"]
            if step.get("uses", "").startswith("actions/checkout@")
        ]

        self.assertEqual(1, len(checkout_steps))
        self.assertEqual(CHECKOUT_ACTION, checkout_steps[0]["uses"])
        self.assertEqual(
            "false",
            checkout_steps[0].get("with", {}).get("persist-credentials"),
        )

    def test_linux_ci_pull_requests_cannot_reach_release(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        jobs = workflow["jobs"]

        self.assertIn("release", jobs)
        release_job = jobs["release"]
        self.assertEqual("build", release_job["needs"])
        self.assertEqual(
            "github.event_name == 'push' && "
            "startsWith(github.ref, 'refs/tags/linux-v')",
            release_job["if"],
        )
        self.assertNotIn(GH_RELEASE_ACTION, str(jobs["build"]))
        self.assertNotIn("secrets.GITHUB_TOKEN", str(jobs["build"]))

    def test_linux_ci_release_is_tag_only_with_write_permission(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        jobs = workflow["jobs"]

        self.assertIn("release", jobs)
        release_job = jobs["release"]
        self.assertEqual(
            "github.event_name == 'push' && "
            "startsWith(github.ref, 'refs/tags/linux-v')",
            release_job["if"],
        )
        self.assertEqual({"contents": "write"}, release_job["permissions"])
        self.assertEqual(
            [
                "Download AppImage artifact",
                "Download binary artifact",
                "Download source tarball artifact",
                "Publish GitHub release",
            ],
            [step["name"] for step in release_job["steps"]],
        )
        self.assertFalse(any("run" in step for step in release_job["steps"]))
        release_steps = named_steps(release_job)
        publish = release_steps["Publish GitHub release"]
        self.assertEqual(GH_RELEASE_ACTION, publish["uses"])
        self.assertEqual("${{ github.ref_name }}", publish["with"]["tag_name"])
        self.assertEqual(
            "${{ secrets.GITHUB_TOKEN }}",
            publish["env"]["GITHUB_TOKEN"],
        )

    def test_linux_ci_hands_exact_build_artifacts_to_release(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        jobs = workflow["jobs"]

        self.assertIn("release", jobs)
        build_job = jobs["build"]
        release_job = jobs["release"]
        build_steps = named_steps(build_job)
        release_steps = named_steps(release_job)

        self.assertEqual(
            "${{ steps.release-version.outputs.version }}",
            build_job["outputs"]["version"],
        )
        self.assertEqual(
            "release-version",
            build_steps["Get release version"]["id"],
        )

        artifact_steps = [
            (
                build_steps["Upload AppImage artifact"],
                release_steps["Download AppImage artifact"],
                "librepods-x86_64.AppImage",
                "linux-rust/dist/librepods-x86_64.AppImage",
            ),
            (
                build_steps["Upload binary artifact"],
                release_steps["Download binary artifact"],
                "librepods",
                "linux-rust/dist/librepods",
            ),
            (
                build_steps["Upload source tarball artifact"],
                release_steps["Download source tarball artifact"],
                "librepods-source",
                "linux-rust/dist/librepods-v"
                "${{ steps.release-version.outputs.version }}-source.tar.gz",
            ),
        ]
        for upload, download, artifact_name, artifact_path in artifact_steps:
            with self.subTest(artifact=artifact_name):
                self.assertEqual(UPLOAD_ARTIFACT_ACTION, upload["uses"])
                self.assertEqual(artifact_name, upload["with"]["name"])
                self.assertEqual(artifact_path, upload["with"]["path"])
                self.assertEqual("error", upload["with"]["if-no-files-found"])
                self.assertEqual(DOWNLOAD_ARTIFACT_ACTION, download["uses"])
                self.assertEqual(artifact_name, download["with"]["name"])
                self.assertEqual("release-assets", download["with"]["path"])

        self.assertNotIn("if", build_steps["Upload AppImage artifact"])
        self.assertNotIn("if", build_steps["Upload binary artifact"])
        self.assertEqual(
            "startsWith(github.ref, 'refs/tags/linux-v')",
            build_steps["Upload source tarball artifact"]["if"],
        )
        self.assertEqual(
            [
                "release-assets/librepods-v"
                "${{ needs.build.outputs.version }}-source.tar.gz",
                "release-assets/librepods-x86_64.AppImage",
                "release-assets/librepods",
            ],
            release_steps["Publish GitHub release"]["with"]["files"].splitlines(),
        )

    def test_nix_updater_checkouts_do_not_persist_credentials(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        checkout_steps = [
            step
            for job in workflow["jobs"].values()
            for step in job["steps"]
            if step.get("uses", "").startswith("actions/checkout@")
        ]

        self.assertEqual(2, len(checkout_steps))
        for checkout in checkout_steps:
            with self.subTest(job_checkout=checkout["name"]):
                self.assertEqual(
                    "false",
                    checkout.get("with", {}).get("persist-credentials"),
                )

    def test_nix_updater_isolates_validation_from_pull_request_writes(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        self.assertIn("create-pull-request", workflow["jobs"])
        update_job = workflow["jobs"]["update-lock"]
        pull_request_job = workflow["jobs"]["create-pull-request"]

        self.assertEqual({}, workflow["permissions"])
        self.assertEqual({"contents": "read"}, update_job["permissions"])
        self.assertEqual(
            {"contents": "write", "pull-requests": "write"},
            pull_request_job["permissions"],
        )
        self.assertEqual("update-lock", pull_request_job["needs"])
        self.assertEqual(
            "${{ steps.lockfile.outputs.changed }}",
            update_job["outputs"]["changed"],
        )
        self.assertEqual(
            "needs.update-lock.outputs.changed == 'true'",
            pull_request_job["if"],
        )
        self.assertFalse(
            any(
                step.get("uses", "").startswith(
                    "peter-evans/create-pull-request@"
                )
                for step in update_job["steps"]
            )
        )
        self.assertEqual(
            [
                "Checkout target branch",
                "Download updated lockfile",
                "Open flake lock update pull request",
            ],
            [step["name"] for step in pull_request_job["steps"]],
        )
        self.assertFalse(any("run" in step for step in pull_request_job["steps"]))

    def test_nix_updater_dispatches_validation_for_token_created_pull_request(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        pull_request_job = workflow["jobs"]["create-pull-request"]
        dispatch_job = workflow["jobs"]["dispatch-pr-validation"]
        create_pull_request = named_steps(pull_request_job)[
            "Open flake lock update pull request"
        ]
        dispatch = named_steps(dispatch_job)["Dispatch Nix PR validation"]

        self.assertEqual("create-pull-request", dispatch_job["needs"])
        self.assertEqual(
            "needs.create-pull-request.outputs.operation == 'created' || "
            "needs.create-pull-request.outputs.operation == 'updated'",
            dispatch_job["if"],
        )
        self.assertEqual(
            {"actions": "write", "contents": "read"},
            dispatch_job["permissions"],
        )
        self.assertEqual("create-pr", create_pull_request["id"])
        self.assertEqual(
            "${{ steps.create-pr.outputs.pull-request-operation }}",
            pull_request_job["outputs"]["operation"],
        )
        self.assertEqual(GITHUB_SCRIPT_ACTION, dispatch["uses"])
        script = dispatch["with"]["script"]
        self.assertIn("github.rest.actions.createWorkflowDispatch", script)
        self.assertIn("workflow_id: 'ci-nix-pr.yml'", script)
        self.assertIn("ref: 'ci/weekly-flake-lock'", script)
        self.assertNotIn("checkout", str(dispatch_job).lower())
        self.assertFalse(any("run" in step for step in dispatch_job["steps"]))

    def test_nix_updater_binds_lockfile_to_validated_source_revision(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        update_job = workflow["jobs"]["update-lock"]
        pull_request_job = workflow["jobs"]["create-pull-request"]
        update_steps = named_steps(update_job)
        pull_request_steps = named_steps(pull_request_job)

        self.assertIn("Record source revision", update_steps)
        self.assertIn("source-sha", update_job["outputs"])
        source_revision = update_steps["Record source revision"]
        self.assertEqual("source", source_revision["id"])
        self.assertEqual(
            'echo "sha=$(git rev-parse HEAD)" >> "$GITHUB_OUTPUT"',
            source_revision["run"].strip(),
        )
        self.assertEqual(
            "${{ steps.source.outputs.sha }}",
            update_job["outputs"]["source-sha"],
        )
        self.assertEqual(
            "${{ needs.update-lock.outputs.source-sha }}",
            pull_request_steps["Checkout target branch"]["with"]["ref"],
        )

    def test_nix_updater_targets_linux_rust(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        self.assertIn("create-pull-request", workflow["jobs"])
        update_steps = named_steps(workflow["jobs"]["update-lock"])
        pull_request_steps = named_steps(workflow["jobs"]["create-pull-request"])

        self.assertEqual(
            "linux/rust",
            update_steps["Checkout target branch"]["with"]["ref"],
        )
        self.assertEqual(
            "linux/rust",
            pull_request_steps["Open flake lock update pull request"]["with"][
                "base"
            ],
        )

    def test_nix_updater_hands_off_only_flake_lock(self):
        workflow = load_yaml(NIX_UPDATE_WORKFLOW)
        self.assertIn("create-pull-request", workflow["jobs"])
        update_job = workflow["jobs"]["update-lock"]
        pull_request_job = workflow["jobs"]["create-pull-request"]
        update_steps = named_steps(update_job)
        pull_request_steps = named_steps(pull_request_job)
        self.assertIn("Upload updated lockfile", update_steps)
        self.assertIn("Download updated lockfile", pull_request_steps)
        upload = update_steps["Upload updated lockfile"]
        download = pull_request_steps["Download updated lockfile"]
        create_pull_request = pull_request_steps[
            "Open flake lock update pull request"
        ]

        self.assertEqual(
            "steps.lockfile.outputs.changed == 'true'",
            upload["if"],
        )
        self.assertEqual("flake-lock-update", upload["with"]["name"])
        self.assertEqual("flake.lock", upload["with"]["path"])
        self.assertEqual("error", upload["with"]["if-no-files-found"])
        self.assertEqual(upload["with"]["name"], download["with"]["name"])
        self.assertEqual(".", download["with"]["path"])
        self.assertEqual("flake.lock", create_pull_request["with"]["add-paths"])

    def test_linux_ci_revalidates_pull_requests_targeting_linux_rust(self):
        workflow = load_yaml(LINUX_WORKFLOW)
        self.assertIn("pull_request", workflow["on"])
        pull_request = workflow["on"]["pull_request"]

        self.assertEqual(["linux/rust"], pull_request["branches"])
        self.assertEqual(workflow["on"]["push"]["paths"], pull_request["paths"])
        for workflow_path in (
            ".github/workflows/ci-nix-pr.yml",
            ".github/workflows/ci-nix-flake-update.yml",
        ):
            self.assertIn(workflow_path, workflow["on"]["push"]["paths"])


if __name__ == "__main__":
    unittest.main()
