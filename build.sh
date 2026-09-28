#!/usr/bin/env bash
# Builds this project with a clean, standard Maven configuration — bypassing the machine's
# own ~/.m2/settings.xml, which mirrors everything to an EU Commission-internal Nexus that
# silently returns HTML login pages for artifacts it can't serve (poisoning ~/.m2/repository
# with corrupt jars/poms instead of failing cleanly). See .mvn/settings.xml for details.
#
# Also uses its own local repository (.mvn/repository) instead of ~/.m2/repository, so this
# build is unaffected by anything the corporate mirror may have already poisoned there, and
# never poisons it further either.
#
# Usage:
#   ./build.sh                 # mvn clean package (default)
#   ./build.sh test
#   ./build.sh -pl backend/kafkatower-app -am package -DskipTests
#   ./build.sh <any other mvn arguments>

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SETTINGS_XML="$SCRIPT_DIR/.mvn/settings.xml"
LOCAL_REPO="$SCRIPT_DIR/.mvn/repository"

mkdir -p "$LOCAL_REPO"

if [ "$#" -eq 0 ]; then
    set -- clean package
fi

exec mvn \
    --settings "$SETTINGS_XML" \
    -Dmaven.repo.local="$LOCAL_REPO" \
    "$@"
