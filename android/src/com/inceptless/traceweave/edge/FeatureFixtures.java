package com.inceptless.traceweave.edge;

import org.json.JSONObject;

final class FeatureFixtures {
    static JSONObject verify() throws Exception {
        FeatureReadings readings = new FeatureReadings();
        for (int index = 0; index < 4; index++) {
            long nanos = (index + 1) * 1000000000L;
            readings.light(index * 2, nanos);
            readings.pressure(1000 + index * 2, nanos);
            readings.windows(nanos);
            readings.windows(nanos);
        }
        if (readings.get("photometric_flicker_kurtosis", 4000000000L).value != -1.36
                || readings.get("photometric_contrast_ratio", 4000000000L).value != 0.56
                || readings.get("barometric_turbulence_variance", 4000000000L).value != 5)
            throw new IllegalStateException("native window population statistics mismatch");
        readings.windows(20000000000L);
        if (readings.get("photometric_flicker_kurtosis", 20000000000L) != null)
            throw new IllegalStateException("stale window retained");
        readings.magnetic(new float[]{30, 0, -40}, 21000000000L);
        if (readings.get("geomagnetic_field_dip_angle_deg", 21000000000L).value != -53.13
                || readings.get("geomagnetic_total_field_flux", 21000000000L).value != 0
                || readings.get("geomagnetic_quadrupole_anisotropy_ratio", 21000000000L).value != 0.75)
            throw new IllegalStateException("magnetic vector formula mismatch");
        readings.magnetic(new float[]{0, 0, 0}, 22000000000L);
        if (readings.get("geomagnetic_field_dip_angle_deg", 22000000000L) != null)
            throw new IllegalStateException("undefined magnetic angle supplied");
        FeatureReadings buoyancy = new FeatureReadings();
        for (int index = 0; index < 6; index++) {
            long nanos = (index + 1) * 1000000000L;
            buoyancy.pressure(1000 + index, nanos);
            buoyancy.windows(nanos);
        }
        if (buoyancy.get("barometric_buoyancy_energy_flux", 6000000000L).value != 8.3769
                || !buoyancy.get("barometric_buoyancy_energy_flux", 6000000000L).kind.equals("proxy"))
            throw new IllegalStateException("barometric proxy formula mismatch");
        buoyancy.windows(12000000000L);
        if (buoyancy.get("barometric_buoyancy_energy_flux", 12000000000L) != null)
            throw new IllegalStateException("stale barometric proxy retained");
        FeatureReadings electrical = new FeatureReadings();
        electrical.electrical(4000, -100000, 1000000000L);
        electrical.electrical(4010, -200000, 2000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 2000000000L).value != 100
                || electrical.get("thermodynamic_joule_heat_loss_watts", 2000000000L).value != 0.004)
            throw new IllegalStateException("electrical proxy units mismatch");
        electrical.electrical(4010, -210000, 3000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 3000000000L) != null)
            throw new IllegalStateException("insufficient electrical excitation supplied");
        FeatureReadings temporal = new FeatureReadings();
        for (int index = 0; index < 8; index++) {
            long nanos = (index + 1) * 1000000000L;
            temporal.accelerometer(new float[]{index, 0, 0}, nanos);
            temporal.windows(nanos);
            if (index < 7 && temporal.get("imu_temporal_autocorrelation_decay", nanos) != null)
                throw new IllegalStateException("ACF warmup supplied");
        }
        if (temporal.get("imu_temporal_autocorrelation_decay", 8000000000L).value != 2.128)
            throw new IllegalStateException("ACF formula mismatch");
        temporal.windows(9000000000L);
        if (temporal.get("imu_temporal_autocorrelation_decay", 9000000000L) != null)
            throw new IllegalStateException("held sensor sample supplied");
        for (int index = 0; index < 8; index++) {
            long nanos = (index + 1) * 1000000000L;
            temporal.accelerometer(new float[]{index, 0, 0}, nanos);
            temporal.windows(nanos);
        }
        if (temporal.get("imu_temporal_autocorrelation_decay", 8000000000L) == null)
            throw new IllegalStateException("ACF clock rollback recovery failed");
        FeatureReadings ordinal = new FeatureReadings();
        int[] sequence = {0, 3, 1, 2, 5, 4, 7, 6, 9, 8, 2, 5, 1, 4, 0, 3};
        for (int index = 0; index < sequence.length; index++) {
            long nanos = (index + 1) * 1000000000L;
            ordinal.accelerometer(new float[]{sequence[index], 0, 0}, nanos);
            ordinal.windows(nanos);
        }
        if (ordinal.get("permutation_entropy_complexity", 16000000000L).value != 0.92)
            throw new IllegalStateException("ordinal entropy formula mismatch");
        ordinal.windows(17000000000L);
        if (ordinal.get("permutation_entropy_complexity", 17000000000L) != null)
            throw new IllegalStateException("held ordinal input supplied");
        FeatureReadings teager = new FeatureReadings();
        int[] signal = {1, 3, 2, 4, 10, 1};
        double[] expected = {Double.NaN, Double.NaN, 7, 0, 0, 10};
        for (int index = 0; index < signal.length; index++) {
            long nanos = (index + 1) * 1000000000L;
            teager.accelerometer(new float[]{signal[index], 0, 0}, nanos);
            teager.windows(nanos);
            FeatureReadings.Reading value = teager.get("non_linear_energy_operator_teager", nanos);
            if (index < 2 ? value != null : value == null || value.value != expected[index])
                throw new IllegalStateException("Teager warmup/formula/clipping mismatch");
        }
        teager.windows(7000000000L);
        if (teager.get("non_linear_energy_operator_teager", 7000000000L) != null)
            throw new IllegalStateException("held Teager input supplied");
        return new JSONObject().put("passed", true).put("scope", "synthetic_magnetic_window_electrical_accelerometer_acf_ordinal_entropy_and_teager_not_live_acquisition");
    }
}
