#!/usr/bin/env bash
# Verification harness: calls public interfaces; never reproduces bin/check.
set -euo pipefail
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
mode="${1:-lifecycle}"
[[ $# -le 1 && "$mode" =~ ^(lifecycle|concurrent)$ ]] || { echo 'Usage: tests/check-lifecycle.sh [concurrent]' >&2; exit 2; }
logs="$(mktemp -d "${TMPDIR:-/tmp}/erbas-lifecycle.XXXXXXXX")"
echo "EVIDENCE_DIR=$logs"

assert_clean() {
    local log="$1" run kind resources query_code filter image
    local options
    run="$(sed -n 's/^RUN_ID=\([^ ]*\).*/\1/p' "$log")"
    [[ "$run" =~ ^erbas-check-[0-9]+-[0-9]+-[0-9]+$ ]] || { echo 'ERROR: missing exact run identity.' >&2; return 1; }
    grep -qx "CLEANUP_STATUS=passed CLEANUP_CODE=0 RUN_ID=$run" "$log"
    for kind in container network volume; do
        options=(--quiet)
        [[ "$kind" != container ]] || options+=(--all)
        for filter in "org.erbas.validation.run=$run" "com.docker.compose.project=$run"; do
            if resources="$(timeout --kill-after=10s 15 docker "$kind" ls "${options[@]}" --filter "label=$filter")"; then
                query_code=0
            else
                query_code=$?
            fi
            [[ "$query_code" == 0 ]] || { echo "ERROR: Docker $kind cleanup query failed (exit $query_code)." >&2; return "$query_code"; }
            [[ -z "$resources" ]] || { echo "ERROR: remaining $kind for $run" >&2; return 1; }
        done
    done
    for image in "${run}-build" "${run}-app"; do
        if resources="$(timeout --kill-after=10s 15 docker image ls --quiet "$image")"; then
            query_code=0
        else
            query_code=$?
        fi
        [[ "$query_code" == 0 ]] || { echo "ERROR: Docker image cleanup query failed (exit $query_code)." >&2; return "$query_code"; }
        [[ -z "$resources" ]] || { echo "ERROR: remaining image tag $image" >&2; return 1; }
    done
    echo "CLEANUP_VERIFIED=$run containers=0 networks=0 volumes=0 image_tags=0"
}

assert_contract_failure() {
    local log="$1" code="$2" runner_code run line stage=0
    runner_code="$(sed -n 's/^CONTRACT_RUN=failure PHASE=database-up EXIT=\([0-9][0-9]*\)$/\1/p' "$log")"
    [[ "$runner_code" =~ ^[1-9][0-9]*$ && "$code" == "$runner_code" ]] || {
        echo 'ERROR: exit does not match an observed shared runner failure.' >&2; return 1;
    }
    run="$(sed -n 's/^RUN_ID=\([^ ]*\).*/\1/p' "$log")"
    local expected=(
        'CONTRACT_PREFLIGHT=passed PHASE=database-up'
        'CONTRACT_RUN=start PHASE=database-up'
        "CONTRACT_RUN=failure PHASE=database-up EXIT=$runner_code"
        "FAILURE_SOURCE=contract-runner PHASE=database-up EXIT=$runner_code"
        "CLEANUP_STATUS=passed CLEANUP_CODE=0 RUN_ID=$run"
        "CHECK_EXIT=$runner_code RUN_ID=$run"
    )
    while IFS= read -r line; do
        if [[ "$line" == "${expected[$stage]}" ]]; then
            stage=$((stage + 1))
            [[ "$stage" != "${#expected[@]}" ]] || break
        fi
    done <"$log"
    [[ "$stage" == "${#expected[@]}" ]] || { echo 'ERROR: incomplete or unordered shared-runner failure evidence.' >&2; return 1; }
    echo "CONTRACT_FAILURE_VERIFIED=bin/test EXIT=$runner_code CLEANUP_CODE=0"
}

if [[ "$mode" == concurrent ]]; then
    "$root/bin/check" >"$logs/first.log" 2>&1 & first=$!
    "$root/bin/check" >"$logs/second.log" 2>&1 & second=$!
    first_code=0; second_code=0
    wait "$first" || first_code=$?
    wait "$second" || second_code=$?
    cat "$logs/first.log" "$logs/second.log"
    assert_clean "$logs/first.log"
    assert_clean "$logs/second.log"
    first_run="$(sed -n 's/^RUN_ID=\([^ ]*\).*/\1/p' "$logs/first.log")"
    second_run="$(sed -n 's/^RUN_ID=\([^ ]*\).*/\1/p' "$logs/second.log")"
    [[ "$first_run" != "$second_run" && "$first_code" == 0 && "$second_code" == 0 ]]
    echo "CONCURRENT=passed first=$first_run second=$second_run"
else
    for scenario in normal startup-timeout contract; do
        args=()
        [[ "$scenario" == normal ]] || args=(--exercise-failure "$scenario")
        code=0
        "$root/bin/check" "${args[@]}" >"$logs/$scenario.log" 2>&1 || code=$?
        cat "$logs/$scenario.log"
        assert_clean "$logs/$scenario.log"
        case "$scenario" in
            normal) [[ "$code" == 0 ]] ;;
            startup-timeout) [[ "$code" == 124 ]]; grep -q '^ERROR: startup timeout' "$logs/$scenario.log" ;;
            contract) assert_contract_failure "$logs/$scenario.log" "$code" ;;
        esac
        echo "SCENARIO_RESULT=$scenario EXIT=$code"
    done
fi
