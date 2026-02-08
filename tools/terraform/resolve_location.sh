#!/bin/bash

set -e

eval "$(jq -r '@sh "TARGET=\(.target)"')"

BAZEL_ARGS=(--platforms=//tools/platforms:linux_x86)

bazel build "${BAZEL_ARGS[@]}" "$TARGET"

WORKSPACE=$(bazel info "${BAZEL_ARGS[@]}" workspace)
QUERY=$(bazel cquery "${BAZEL_ARGS[@]}" --output=files "$TARGET" 2>/dev/null)
LOCATION="$WORKSPACE/$QUERY"

jq -n --arg location "$LOCATION" '{"location":$location}'
