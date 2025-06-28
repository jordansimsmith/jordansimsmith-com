#!/bin/bash

set -e

eval "$(jq -r '@sh "TARGET=\(.target)"')"

bazel build "$TARGET" --platforms=//tools/platforms:linux_x86

WORKSPACE=$(bazel info workspace)
QUERY=$(bazel cquery "$TARGET" --output=files 2>/dev/null)
LOCATION="$WORKSPACE/$QUERY"

jq -n --arg location "$LOCATION" '{"location":$location}'
