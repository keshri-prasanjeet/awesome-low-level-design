#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
java src/LLDRunner.java "$@"
