# Self-hosted CI runner

CI for this repository is split between a self-hosted runner and GitHub-hosted
runners:

- `contract` → `self-hosted, dad, linux` (Node foundation checks).
- `android` → **GitHub-hosted `ubuntu-24.04`** (unchanged).

## Why the `android` job stays GitHub-hosted

The `android` job boots an **x86_64 Android emulator** and relies on Linux
hardware acceleration: it `chmod a+rw /dev/kvm` and runs the emulator with
`-gpu swiftshader_indirect`. The current self-hosted host is an Apple Silicon
Mac, which has **no `/dev/kvm`**, and the x86_64 system image will not run under
macOS HVF. Moving this job to self-hosted therefore requires a **Linux host with
KVM** (nested virtualization enabled). Until such a host exists, keep this job on
`ubuntu-24.04` — flipping it later is a one-line `runs-on` change.

## Runner prerequisites (contract job)

- **Node.js 24** is installed per-job by `actions/setup-node`; no host-level
  Android SDK or JDK is needed for `contract`.

## Registration

`CoachCoe` is a GitHub user account, so the runner is registered per-repository.
Register from **Settings → Actions → Runners → New self-hosted runner**:

```sh
./config.sh --url https://github.com/CoachCoe/DAD-Android \
  --token <TOKEN_FROM_THE_RUNNERS_PAGE> \
  --labels dad,linux --name dad-android-1
./svc.sh install && ./svc.sh start
```

Do **not** merge this branch until the runner shows **online**, or the
`contract` job will hang waiting for a matching runner.
