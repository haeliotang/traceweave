#!/bin/bash
set -euo pipefail

echo "========================================================================"
echo "PIXEL SENSORIUM PHYSICAL OBSERVATION STACK v1 FROZEN AUTHORITY"
echo "========================================================================"

# Upstream frozen substrate verifications
/root/verify_pixel_frozen.sh > /dev/null
/root/verify_production_integration_v1_frozen.sh > /dev/null
/root/verify_pixel_orchestrator_frozen.sh > /dev/null
/root/verify_pixel_runtime_frozen.sh > /dev/null
/root/verify_pixel_control_adapter_frozen.sh > /dev/null
/root/verify_android_deployment_client_frozen.sh > /dev/null
/root/verify_android_desktop_integration_v1_frozen.sh > /dev/null
/root/verify_android_desktop_integration_v2_frozen.sh > /dev/null
/root/verify_deployment_supervision_v1_frozen.sh > /dev/null
/root/verify_android_desktop_integration_v3_frozen.sh > /dev/null

echo "Upstream Substrate Verification: 10/10 PASS"

declare -A EXPECTED_HASHES=(
    ["/root/pixel_physical_capability_inventory_v1.json"]="0d97fc2a511fd0c3a608d279405e68290f2895c571d4f9e59f3fd53d0084c0d8"
    ["/root/physical_observation_stack_v1_design.md"]="35674400c8004004ed0e09b24113fd1ba0e59c857b6b2f125889823dcc7391ed"
    ["/root/physicalctl.py"]="312861c5dd23b8c5b1c54933731941d9ef192d87195b970ab449516197deaa98"
    ["/root/pixel_physical_runner_v1.py"]="7ca3bab32e0786fcd651c7da6a8513ffbb52bea6f46f32da68b534198ac0452c"
    ["/root/test_physical_observation_contract_v1.py"]="2340218fb79743b6638fef781d0cb51b3fa47413a41b7bdeb1e6a675e2843e2b"
    ["/root/audit_physical_observation_static_authority_v1.py"]="6017689ebdfc06da7b2f327c17b424aefa0479b35ef8c5757a38dbc1f521340f"
    ["/root/audit_physical_observation_adversarial_v1.py"]="eb3cc0953dc9410f68309a2ccff2e5b756d11c5ec6956448f9fa1263dc60f691"
    ["/root/audit_physical_transition_quality_v1.py"]="d0291893ebe8fd5f33505ac917c93ce1ca984535ee3b5e690f890b6c5d6a7dbf"
    ["/root/audit_physical_resource_usage_v1.py"]="a9e57b51d8a56134be8a12d90289815da1692d52dd0af56f9ed52f24c16da2c5"
    ["/root/audit_physical_24h_soak_v1.py"]="d8bc4950cc0f3dd0de019c6cd5d000db90a719bda7c1815e815d7fff30358d95"
    ["/root/audit_physical_capture_integration_v1.py"]="27788b0e822d0481aba36b811d7e69efa95bcf121ad39aa4725711d9e2f437e7"
    ["/root/audit_physical_location_stability_v1.py"]="872d27ccc0b34ba5224640a0f984ac9be82066dba01bb5fc09f5725cb1bb172b"
    ["/root/audit_physical_audio_control_v1.py"]="ca87847503b40aa0aac5569106a218e851899f33552c12d80d1d038e66f18846"
    ["/root/audit_physical_trajectory_provenance_v1.py"]="bd1cbe8c661fced8c5f353264f08e345571017e0ca1cd58d81e02f04156a6281"
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
    printf "%-55s %s\n" "$(basename "$file")" "$actual"
done

# Verify implementation tree hash
EXPECTED_TREE="08c455ae8d3cf068954c6065bce3bcb7c2ba658870f6758049eae80284b9f14f"
ACTUAL_TREE=$(find /root/pixel_physical -name "*.py" | sort | xargs sha256sum | sha256sum | cut -d ' ' -f 1)
if [[ "$ACTUAL_TREE" != "$EXPECTED_TREE" ]]; then
    echo "IMPLEMENTATION TREE HASH MISMATCH"
    echo "  Expected: $EXPECTED_TREE"
    echo "  Actual:   $ACTUAL_TREE"
    exit 1
fi
printf "%-55s %s\n" "pixel_physical/*.py (tree hash)" "$ACTUAL_TREE"

echo
echo "PHYSICAL_OBSERVATION_STACK_V1_FROZEN=PASS"
