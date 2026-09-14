# Devbox setup

This directory provisions a Debian 13 development box for this repository. It installs Bazelisk, the native build toolchain, pinned Docker packages, and Docker access for the developer account.

## Setup

Run from the repository root as the developer user:

```bash
./tools/devbox/install.sh
```

The script requires a systemd-capable Debian 13 host, `sudo`, and outbound access to Debian, Docker, GitHub, Bazel, Maven, npm, PyPI, and container registries.

Log out and back in once it completes so the current user receives Docker-group membership. Then verify the host and repository:

```bash
docker run --rm hello-world
bazel test //...
```

The repository's Bazel configuration supplies its Java, Python, Node, and pnpm toolchains. No host installation of those tools is required to run the test suite.

## Updating provisioned tools

Tool versions and Bazelisk checksums are pinned in `vars.yml`. Update them together only after validating the setup on a fresh Debian 13 devbox.
