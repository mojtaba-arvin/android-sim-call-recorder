## Consolidated SDK/CI acceptance batch — 2026-11-25T13:20:00Z

- PR #64 now also integrates the previously separate PR #41, #47 and #49 scopes to avoid another full CI cycle: safe Android CI path classification, an external Release-AAR consumer smoke app, and release AAR checksum verification.
- Integration code commit: `8c125b9f8918af98504bdc0fbb47fa3435405900`; benchmark and this snapshot must pin this SHA. The next commit is snapshot/benchmark-only.
- Classifier deliberately excludes only known non-Android paths and two dedicated non-Android Python tests. It no longer excludes all `scripts/tests/*`; emulator preflight and release-asset verifier test changes must trigger full Android CI. Unknown paths fail safe to full CI.
- SDK consumer smoke app consumes the generated Release AAR by file dependency and uses only public SDK/domain packages. It uses platform `android.app.Activity` to avoid an undeclared AndroidX Activity dependency. CI assembles it after the SDK Release AAR.
- Release AAR verifier now rejects empty AARs, symlinked AARs/manifests, checksum mismatch, malformed/multiple entries and unexpected top-level files. Release build and isolated publish jobs both verify the bundle; shell regression tests include empty/symlink cases.
- The PR #64 code batch now includes the changes formerly tracked in #41, #47 and #49; close those PRs as superseded only after the consolidated exact-head CI is green and #64 merges.
- Do not merge or close any stacked PR based on a prior head's CI. Wait for exact latest-head Android CI and Agent System Contracts.
- Efficiency policy: run one full CI after the complete integration batch; while it is running, inspect independent issues/PRs and docs. Retry only transient failed jobs. Do not rerun successful emulator lanes for SDK unit-test hangs.

