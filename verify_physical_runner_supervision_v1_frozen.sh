#!/bin/bash
set -euo pipefail

echo "========================================================================"
echo "PIXEL SENSORIUM PHYSICAL RUNNER SUPERVISION v1 FROZEN AUTHORITY"
echo "========================================================================"

# Upstream frozen substrate verifications
/root/verify_physical_observation_stack_v1_frozen.sh > /dev/null

echo "Upstream Substrate Verification: 11/11 PASS"

declare -A EXPECTED_HASHES=(
    ["/root/physical_runner_supervision_v1_design.md"]="07189bd43e5d97e85ed940fefab09dbdb40cae4a914c1f82a8df2a1831562ee2"
    ["/root/pixel_physical_supervision_v1.py"]="91b3ca26c6023895269325b29f06926b66d445c78cbfc8af2442186ed978f106"
    ["/root/physicalsupervisionctl.py"]="30363edf020aaa6464e9bfc3770c73dc20e4f79ff7be7a21b58126826e2cac2a"
    ["/root/pixel_physical_supervision_job_v1"]="8732ba55904c013d83d169ff204a1cc66c3034546450a5564032b8654546d1a8"
    ["/root/test_physical_runner_supervision_contract_v1.py"]="b9acfac7034956a60ee4a900162dc4134149e23c70966d0cf6b3b8c8d5ed6333"
    ["/root/audit_physical_runner_supervision_static_authority_v1.py"]="0e71d959209c7cf0a56491edb34d85e1b4482f698a25c3da752ef280c02c2f66"
    ["/root/audit_physical_runner_supervision_adversarial_v1.py"]="39ff3cb8da0e0e237db1eaa1b5b660928a8e85ca80fedbeb148184d6a0a6c572"
    ["/root/audit_physical_runner_supervision_live_recovery_v1.py"]="e523dae010bc8ef165ca403037b277a9b87c7e8c3a8d6761efaa4c6850d27092"
    ["/root/audit_physical_runner_supervision_stop_v1.py"]="a0e029353588f440e986f9f3beb85bd4b24027038f335e43750c41381cac0cc4"
    ["/root/audit_physical_runner_supervision_jobscheduler_recovery_v1.py"]="92f24dc256e0a7c0431c30500bb96db5ffe5f667e8398588529def1005c31e4c"
    ["/root/audit_physical_runner_supervision_clock_v1.py"]="118a110e4dcd76ad1a8caa72972fe66458f4117af6aa9460e6fd2ab2e94c2fb4"
    ["/root/audit_physical_runner_supervision_recovery_state_v1.py"]="b4785dd14f2ad3f1a8c2ca4c1253249550e383a20aa065439d922ecd433b5939"
    ["/root/audit_physical_runner_supervision_boot_epoch_v1.py"]="92ca892c520af2a9591f819c3e223eca4e52cdaf9ba80cdc0ad474fbd055e905"
    ["/root/physical_runner_supervision_v1/PHYSICAL_RUNNER_SUPERVISION_V1_IMPLEMENTATION_FREEZE.txt"]="ba87fcb1d5a1058e22c04a1ddc0642d80dcf807f3762ee6a300b41e5f70d2abd"
)

for file in "${!EXPECTED_HASHES[@]}"; do
    expected="${EXPECTED_HASHES[$file]}"
    actual=$(sha256sum "$file" | cut -d ' ' -f 1)
    if [[ "$actual" != "$expected" ]]; then
        echo "HASH MISMATCH: $file"
        echo "  Expected: $expected"
        echo "  Actual:   $actual"
        exit 1
    fi
    printf "%-65s %s\n" "$(basename "$file")" "$actual"
done

echo
echo "PHYSICAL_RUNNER_SUPERVISION_V1_FROZEN=PASS"
