#!/usr/bin/env bash

set -euo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

if [[ "${EUID}" -eq 0 ]]; then
  echo "Run this script as the developer user, not root." >&2
  exit 1
fi

if [[ ! -r /etc/os-release ]]; then
  echo "This setup requires Debian 13." >&2
  exit 1
fi

. /etc/os-release

if [[ "${ID}" != "debian" || "${VERSION_ID}" != "13" ]]; then
  echo "This setup requires Debian 13; found ${PRETTY_NAME:-an unknown distribution}." >&2
  exit 1
fi

if [[ "$(ps -p 1 -o comm=)" != "systemd" ]]; then
  echo "This setup requires a systemd-capable devbox to run Docker." >&2
  exit 1
fi

if ! command -v sudo >/dev/null; then
  echo "This setup requires sudo." >&2
  exit 1
fi

sudo apt-get update
sudo apt-get install --yes ansible
sudo ansible-playbook \
  --connection=local \
  --inventory localhost, \
  --extra-vars "devbox_user=${USER}" \
  "${script_directory}/playbook.yml"

cat <<'EOF'

Setup complete. Log out and back in before using Docker without sudo, then run:

  docker run --rm hello-world
  pip --version
  pnpm --version
  chromium --version
  playwright --version
  playwright install --list
  pnpm install
  bazel test //...
EOF
