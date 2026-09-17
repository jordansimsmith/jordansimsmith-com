# Devbox setup

This directory provisions a Debian 13 development box for this repository. It installs Bazelisk, Node.js, pinned Corepack, pnpm and Playwright, Chromium, the native build toolchain, pinned Docker packages, and Docker access for the developer account.

## Setup

Run from the repository root as the developer user:

```bash
./tools/devbox/install.sh
```

The script requires a systemd-capable Debian 13 host, `sudo`, and outbound access to Debian, Docker, GitHub, Bazel, Maven, npm, PyPI, and container registries.

Log out and back in once it completes so the current user receives Docker-group membership. Then verify the host and repository:

```bash
docker run --rm hello-world
pip --version
terraform --version
pnpm --version
chromium --version
playwright --version
playwright install --list
pnpm install
bazel test //...
```

The host Terraform, Python pip and virtual-environment tools, Node.js, pnpm, Chromium, and Playwright installations support local development and browser-driven UI inspection. Provisioning installs both Debian's Chromium browser and Playwright's matching Chromium runtime under the developer account. The repository's Bazel configuration still supplies Java, Python, Node, and pnpm toolchains for hermetic builds and tests.

## Updating provisioned tools

Tool versions and Bazelisk checksums are pinned in `vars.yml`. Chromium follows the Debian security repository rather than being pinned. Update provisioned versions only after validating the setup on a fresh Debian 13 devbox.
