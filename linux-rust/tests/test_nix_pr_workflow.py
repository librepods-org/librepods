#!/usr/bin/env python3
"""Static contract tests for pull-request Nix validation."""

from pathlib import Path
import re
import unittest

import yaml


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
WORKFLOW_PATH = REPOSITORY_ROOT / ".github" / "workflows" / "ci-nix-pr.yml"

CHECKOUT_ACTION = (
    "actions/checkout@11d5960a326750d5838078e36cf38b85af677262"  # v4
)
NIX_INSTALLER_ACTION = (
    "DeterminateSystems/nix-installer-action@"
    "3138316df39ed29be04236d7ffc686fa525866aa"  # v23
)
GITHUB_SCRIPT_ACTION = (
    "actions/github-script@3a2844b7e9c422d3c10d287c895573f7108da1b3"  # v9.0.0
)
IMMUTABLE_ACTION_REFERENCE = re.compile(
    r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+@[0-9a-f]{40}$"
)


def load_workflow():
    """Load YAML without YAML 1.1 coercing the GitHub `on` key."""
    if not WORKFLOW_PATH.exists():
        raise AssertionError("the dedicated Nix PR validation workflow is missing")
    return yaml.load(WORKFLOW_PATH.read_text(encoding="utf-8"), Loader=yaml.BaseLoader)


def validation_job(workflow):
    """Return the workflow's only job."""
    return workflow["jobs"]["validate"]


class NixPullRequestWorkflowTests(unittest.TestCase):
    def test_trigger_is_limited_to_relevant_linux_rust_pull_requests(self):
        workflow = load_workflow()

        self.assertEqual(
            ["pull_request", "workflow_dispatch"],
            list(workflow["on"]),
        )
        self.assertEqual(
            {
                "branches": ["linux/rust"],
                "paths": [
                    "flake.nix",
                    "flake.lock",
                    "linux-rust/**",
                    ".github/workflows/ci-nix-pr.yml",
                ],
            },
            workflow["on"]["pull_request"],
        )
        self.assertEqual({}, workflow["on"]["workflow_dispatch"])

    def test_dispatched_validation_merges_the_current_target_branch(self):
        workflow = load_workflow()
        merge_step = next(
            step
            for step in validation_job(workflow)["steps"]
            if step.get("name") == "Merge current target branch"
        )

        self.assertEqual({"BASE_REF": "linux/rust"}, merge_step["env"])
        command = merge_step["run"]
        self.assertIn(
            'git fetch --no-tags origin "+refs/heads/${BASE_REF}:'
            'refs/remotes/origin/${BASE_REF}"',
            command,
        )
        self.assertIn(
            'git merge --no-commit --no-ff "origin/${BASE_REF}"',
            command,
        )

    def test_validation_job_has_only_read_access(self):
        workflow = load_workflow()

        self.assertEqual({}, workflow["permissions"])
        self.assertEqual(["validate", "report"], list(workflow["jobs"]))
        self.assertEqual(
            {"contents": "read"},
            validation_job(workflow)["permissions"],
        )

    def test_status_reporting_is_isolated_from_untrusted_validation(self):
        workflow = load_workflow()
        report = workflow["jobs"]["report"]

        self.assertEqual("validate", report["needs"])
        self.assertEqual(
            "always() && github.event_name == 'workflow_dispatch'",
            report["if"],
        )
        self.assertEqual({"statuses": "write"}, report["permissions"])
        self.assertEqual(1, len(report["steps"]))
        step = report["steps"][0]
        self.assertEqual(GITHUB_SCRIPT_ACTION, step["uses"])
        script = step["with"]["script"]
        self.assertIn("github.rest.repos.createCommitStatus", script)
        self.assertIn("sha: context.sha", script)
        self.assertIn("context: 'Nix PR validation / merge'", script)
        self.assertNotIn("actions/checkout", str(report))
        self.assertFalse(any("run" in item for item in report["steps"]))

    def test_actions_use_reviewed_immutable_commits(self):
        workflow = load_workflow()
        action_references = [
            step["uses"]
            for step in validation_job(workflow)["steps"]
            if "uses" in step
        ]

        self.assertEqual(
            [CHECKOUT_ACTION, NIX_INSTALLER_ACTION],
            action_references,
        )
        for action_reference in action_references:
            with self.subTest(action=action_reference):
                self.assertRegex(action_reference, IMMUTABLE_ACTION_REFERENCE)

    def test_checkout_does_not_persist_credentials(self):
        workflow = load_workflow()
        checkout_steps = [
            step
            for step in validation_job(workflow)["steps"]
            if step.get("uses", "").startswith("actions/checkout@")
        ]

        self.assertEqual(1, len(checkout_steps))
        self.assertEqual(
            {"persist-credentials": "false", "fetch-depth": "0"},
            checkout_steps[0]["with"],
        )

    def test_validation_runs_only_fixed_nix_commands(self):
        workflow = load_workflow()
        job = validation_job(workflow)
        run_steps = [
            step
            for step in job["steps"]
            if step.get("name") in {"Validate flake", "Build package"}
        ]

        self.assertEqual(
            [
                "nix flake check --no-build --no-update-lock-file",
                "nix build .#packages.x86_64-linux.default --no-link "
                "--no-update-lock-file",
            ],
            [step["run"].strip() for step in run_steps],
        )
        self.assertNotIn("env", job)
        for step in run_steps:
            with self.subTest(step=step["name"]):
                self.assertNotIn("env", step)
                self.assertNotIn("${{", step["run"])

    def test_validation_runs_contract_tests_and_rejects_lockfile_mutation(self):
        workflow = load_workflow()
        steps = {
            step["name"]: step
            for step in validation_job(workflow)["steps"]
            if "name" in step
        }

        self.assertEqual(
            "python3 -m unittest linux-rust/tests/test_nix_pr_workflow.py -v",
            steps["Run workflow contract tests"]["run"].strip(),
        )
        self.assertEqual(
            "git diff --exit-code -- flake.lock",
            steps["Verify lockfile stayed unchanged"]["run"].strip(),
        )

    def test_validation_has_no_secret_or_release_behavior(self):
        workflow = load_workflow()
        serialized_workflow = WORKFLOW_PATH.read_text(encoding="utf-8")

        self.assertNotIn("secrets.", serialized_workflow)
        self.assertNotIn("GITHUB_TOKEN", serialized_workflow)
        self.assertNotIn("release", str(workflow).lower())


if __name__ == "__main__":
    unittest.main()
