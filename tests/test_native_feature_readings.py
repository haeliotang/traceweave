from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeFeatureReadings(unittest.TestCase):
    def test_freshness_and_evidence_kind(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "ReadingsTest.java"
            harness.write_text('''package org.traceform.edge;
public final class ReadingsTest {
    public static void main(String[] args) {
        FeatureReadings readings = new FeatureReadings();
        readings.put("motion", 2, "derived", "linear_acceleration", 100, 50);
        if (readings.get("motion", 150) == null) throw new AssertionError("cutoff equality");
        if (readings.get("motion", 151) != null) throw new AssertionError("stale reading");
        if (readings.get("motion", 99) != null) throw new AssertionError("future reading");
        if (!readings.get("motion", 120).kind.equals("derived")) throw new AssertionError("kind");
        readings.put("motion", Double.NaN, "derived", "linear_acceleration", 120, 50);
        if (readings.get("motion", 120) != null) throw new AssertionError("invalid replacement");
        if (readings.get("absent", 120) != null) throw new AssertionError("missing input");
        readings.gyroscope(new float[]{1, 2, 3}, 200);
        if (readings.get("rotational_kinetic_energy", 200).value != 7) throw new AssertionError("rotational energy");
        readings.gyroscope(new float[]{100, 0, 0}, 200);
        if (readings.get("rotational_kinetic_energy", 200).value != 50) throw new AssertionError("energy cap");
        readings.accelerometer(new float[]{0, 9.8f, 0}, 200);
        if (readings.get("device_zenith_tilt_angle", 200).value != 90) throw new AssertionError("tilt");
        if (readings.get("device_screen_gravity_alignment", 200).value != 0) throw new AssertionError("alignment");
        readings.accelerometer(new float[]{0, 0, -9.8f}, 200);
        if (readings.get("device_zenith_tilt_angle", 200).value != 180) throw new AssertionError("inverse tilt");
        readings.accelerometer(new float[]{0, 0, 0}, 201);
        if (readings.get("device_zenith_tilt_angle", 201) != null) throw new AssertionError("undefined orientation");
        readings.gyroscope(new float[]{Float.NaN, 0, 0}, 201);
        if (readings.get("rotational_kinetic_energy", 201) != null) throw new AssertionError("invalid gyro");
        readings.clock(java.time.Instant.parse("2026-01-05T12:00:00Z").toEpochMilli(), 300);
        if (Math.abs(readings.get("circadian_sin_phase", 300).value) > 1e-12) throw new AssertionError("daily sine");
        if (Math.abs(readings.get("circadian_cos_phase", 300).value + 1) > 1e-12) throw new AssertionError("daily cosine");
        if (Math.abs(readings.get("infradian_weekly_phase", 300).value - Math.sin(Math.PI / 7)) > 1e-12) throw new AssertionError("Monday weekly phase");
        readings.accelerometer(new float[]{0, 0, 9.80665f}, 1000000000L);
        readings.gyroscope(new float[]{0, 0, 0}, 1000000000L);
        readings.motion(1000000000L);
        if (readings.get("motion_energy", 1000000000L).value != 0) throw new AssertionError("rest energy");
        if (readings.get("motion_stationary_likelihood", 1000000000L).value != 1) throw new AssertionError("rest score");
        readings.gyroscope(new float[]{0, 0, 2}, 1000000000L);
        readings.motion(1000000000L);
        if (readings.get("motion_energy", 1000000000L).value != 2) throw new AssertionError("gyro contribution");
        readings.motion(7000000000L);
        if (readings.get("motion_energy", 7000000000L) != null) throw new AssertionError("stale pair");
        readings.magnetic(new float[]{0, 0, 50}, 1000000000L);
        if (readings.get("geomagnetic_flux_divergence", 1000000000L).value != 5) throw new AssertionError("magnetic reference anomaly");
        if (readings.get("geomagnetic_total_field_flux", 1000000000L).value != 0) throw new AssertionError("50uT reference anomaly");
        if (readings.get("geomagnetic_field_dip_angle_deg", 1000000000L).value != 90) throw new AssertionError("vertical magnetic inclination");
        readings.magnetic(new float[]{30, 0, -40}, 2000000000L);
        if (readings.get("geomagnetic_field_dip_angle_deg", 2000000000L).value != -53.13) throw new AssertionError("signed magnetic inclination");
        if (readings.get("geomagnetic_quadrupole_anisotropy_ratio", 2000000000L).value != 0.75) throw new AssertionError("magnetic component ratio");
        readings.magnetic(new float[]{30, 0, 0}, 2000000000L);
        if (readings.get("geomagnetic_quadrupole_anisotropy_ratio", 2000000000L).value != 10) throw new AssertionError("magnetic ratio cap");
        if (!readings.get("geomagnetic_total_field_flux", 2000000000L).kind.equals("proxy")) throw new AssertionError("reference anomaly kind");
        readings.magnetic(new float[]{0, 0, 0}, 3000000000L);
        if (readings.get("geomagnetic_field_dip_angle_deg", 3000000000L) != null) throw new AssertionError("zero field undefined angle");
        readings.magnetic(new float[]{Float.NaN, 0, 0}, 4000000000L);
        if (readings.get("geomagnetic_quadrupole_anisotropy_ratio", 4000000000L) != null) throw new AssertionError("invalid magnetic ratio removed");
        if (readings.get("geomagnetic_total_field_flux", 4000000000L) != null) throw new AssertionError("invalid magnetic replacement");
        readings.pressure(1000, 1000000000L);
        if (readings.get("barometric_vertical_velocity", 1000000000L) != null) throw new AssertionError("initial pressure derivative");
        readings.pressure(1001, 6000000000L);
        if (readings.get("barometric_vertical_velocity", 6000000000L).value != -1.686) throw new AssertionError("pressure derivative units");
        readings.pressure(Double.NaN, 7000000000L);
        if (readings.get("barometric_vertical_velocity", 7000000000L) != null) throw new AssertionError("invalid pressure resets");
        FeatureReadings buoyancy = new FeatureReadings();
        for (int index = 0; index < 6; index++) {
            long nanos = (index + 1) * 1000000000L;
            buoyancy.pressure(1000 + index, nanos);
            buoyancy.windows(nanos);
            if (index < 5 && buoyancy.get("barometric_buoyancy_energy_flux", nanos) != null) throw new AssertionError("buoyancy derivative warmup");
        }
        if (buoyancy.get("barometric_buoyancy_energy_flux", 6000000000L).value != 8.3769) throw new AssertionError("buoyancy with capped pressure velocity");
        if (!buoyancy.get("barometric_buoyancy_energy_flux", 6000000000L).kind.equals("proxy")) throw new AssertionError("buoyancy evidence kind");
        buoyancy.windows(12000000000L);
        if (buoyancy.get("barometric_buoyancy_energy_flux", 12000000000L) != null) throw new AssertionError("buoyancy stale inputs");
        FeatureReadings changes = new FeatureReadings();
        changes.accelerometer(new float[]{0, 0, 9}, 1000000000L);
        changes.accelerometer(new float[]{3, 4, 9}, 3000000000L);
        if (changes.get("kinematic_jerk_metric", 3000000000L).value != 2.5) throw new AssertionError("linear derivative");
        changes.gyroscope(new float[]{0, 0, 0}, 1000000000L);
        changes.gyroscope(new float[]{0, 0, 4}, 3000000000L);
        if (changes.get("rotational_angular_jerk", 3000000000L).value != 2) throw new AssertionError("angular derivative");
        changes.accelerometer(new float[]{Float.NaN, 0, 0}, 4000000000L);
        if (changes.get("kinematic_jerk_metric", 4000000000L) != null) throw new AssertionError("invalid derivative reset");
        FeatureReadings windows = new FeatureReadings();
        for (int index = 0; index < 4; index++) {
            long nanos = (index + 1) * 1000000000L;
            windows.light(index * 2, nanos);
            windows.pressure(1000 + index * 2, nanos);
            windows.windows(nanos);
            windows.windows(nanos);
            if (index == 0 && windows.get("photometric_contrast_ratio", nanos) != null) throw new AssertionError("window warmup");
        }
        if (windows.get("photometric_flicker_kurtosis", 4000000000L).value != -1.36) throw new AssertionError("population excess kurtosis");
        if (windows.get("photometric_contrast_ratio", 4000000000L).value != 0.56) throw new AssertionError("light contrast");
        if (windows.get("barometric_turbulence_variance", 4000000000L).value != 5) throw new AssertionError("pressure population variance");
        for (int index = 4; index < 13; index++) {
            long nanos = (index + 1) * 1000000000L;
            windows.light(index * 2, nanos);
            windows.pressure(1000 + index, nanos);
            windows.windows(nanos);
        }
        if (windows.get("barometric_turbulence_variance", 13000000000L).value != 2.9167) throw new AssertionError("six-frame bounded window");
        windows.windows(20000000000L);
        if (windows.get("photometric_contrast_ratio", 20000000000L) != null) throw new AssertionError("stale light window");
        windows.light(100, 21000000000L);
        windows.pressure(1000, 21000000000L);
        windows.windows(21000000000L);
        if (windows.get("barometric_turbulence_variance", 21000000000L) != null) throw new AssertionError("gap reset");
        windows.light(Double.NaN, 22000000000L);
        windows.windows(22000000000L);
        if (windows.get("env_log_lux", 22000000000L) != null) throw new AssertionError("invalid light missing");
        FeatureReadings electrical = new FeatureReadings();
        electrical.electrical(4000, -100000, 1000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 1000000000L) != null) throw new AssertionError("resistance warmup");
        electrical.electrical(4010, -200000, 2000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 2000000000L).value != 100
                || electrical.get("thermodynamic_joule_heat_loss_watts", 2000000000L).value != 0.004) throw new AssertionError("electrical units");
        if (!electrical.get("thermodynamic_joule_heat_loss_watts", 2000000000L).kind.equals("proxy")) throw new AssertionError("heat provenance");
        electrical.electrical(4010, -210000, 3000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 3000000000L) != null) throw new AssertionError("insufficient current change");
        electrical.electrical(4500, 10000000, 4000000000L);
        if (electrical.get("thermodynamic_joule_heat_loss_watts", 4000000000L).value != 5) throw new AssertionError("heat cap");
        electrical.electrical(4000, -200000, 10000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 10000000000L) != null) throw new AssertionError("electrical gap");
        electrical.electrical(Double.NaN, -100000, 11000000000L);
        electrical.electrical(4000, -200000, 12000000000L);
        if (electrical.get("battery_internal_impedance_miliohm", 12000000000L) != null) throw new AssertionError("electrical invalid reset");
        FeatureReadings temporal = new FeatureReadings();
        for (int index = 0; index < 8; index++) {
            long nanos = (index + 1) * 1000000000L;
            temporal.accelerometer(new float[]{index, 0, 0}, nanos);
            temporal.windows(nanos);
            temporal.windows(nanos);
            if (index < 7 && temporal.get("imu_temporal_autocorrelation_decay", nanos) != null) throw new AssertionError("ACF warmup");
        }
        if (temporal.get("imu_temporal_autocorrelation_decay", 8000000000L).value != 2.128) throw new AssertionError("lag-one ACF decay");
        if (!temporal.get("imu_temporal_autocorrelation_decay", 8000000000L).kind.equals("derived")) throw new AssertionError("ACF evidence");
        temporal.windows(9000000000L);
        if (temporal.get("imu_temporal_autocorrelation_decay", 9000000000L) != null) throw new AssertionError("held sensor sample");
        for (int index = 0; index < 8; index++) {
            long nanos = (index + 10) * 1000000000L;
            temporal.accelerometer(new float[]{2, 0, 0}, nanos);
            temporal.windows(nanos);
        }
        if (temporal.get("imu_temporal_autocorrelation_decay", 17000000000L) != null) throw new AssertionError("flat ACF undefined");
        for (int index = 0; index < 30; index++) {
            long nanos = (index + 18) * 1000000000L;
            temporal.accelerometer(new float[]{index, 0, 0}, nanos);
            temporal.windows(nanos);
        }
        double rho25 = 22.0 / 25;
        double expected25 = Math.rint(-1 / Math.log(rho25) * 1000) / 1000;
        if (temporal.get("imu_temporal_autocorrelation_decay", 47000000000L).value != expected25) throw new AssertionError("bounded 25-frame ACF");
        temporal.accelerometer(new float[]{31, 0, 0}, 54000000000L);
        temporal.windows(54000000000L);
        if (temporal.get("imu_temporal_autocorrelation_decay", 54000000000L) != null) throw new AssertionError("ACF gap reset");
        for (int index = 0; index < 8; index++) {
            long nanos = (index + 1) * 1000000000L;
            temporal.accelerometer(new float[]{index, 0, 0}, nanos);
            temporal.windows(nanos);
        }
        if (temporal.get("imu_temporal_autocorrelation_decay", 8000000000L) == null) throw new AssertionError("ACF clock rollback recovery");
        temporal.accelerometer(new float[]{Float.NaN, 0, 0}, 55000000000L);
        temporal.windows(55000000000L);
        if (temporal.get("imu_temporal_autocorrelation_decay", 55000000000L) != null) throw new AssertionError("ACF invalid input");
        FeatureReadings ordinal = new FeatureReadings();
        for (int index = 0; index < 10; index++) {
            long nanos = (index + 1) * 1000000000L;
            ordinal.accelerometer(new float[]{index, 0, 0}, nanos);
            ordinal.windows(nanos);
            if (index < 9 && ordinal.get("permutation_entropy_complexity", nanos) != null) throw new AssertionError("ordinal warmup");
        }
        if (ordinal.get("permutation_entropy_complexity", 10000000000L).value != 0) throw new AssertionError("monotonic ordinal entropy");
        ordinal.windows(11000000000L);
        if (ordinal.get("permutation_entropy_complexity", 11000000000L) != null) throw new AssertionError("held ordinal input");
        FeatureReadings tied = new FeatureReadings();
        for (int index = 0; index < 30; index++) {
            long nanos = (index + 1) * 1000000000L;
            tied.accelerometer(new float[]{index % 2, 0, 0}, nanos);
            tied.windows(nanos);
        }
        if (tied.get("permutation_entropy_complexity", 30000000000L) != null) throw new AssertionError("tied ordinal groups excluded");
        FeatureReadings varied = new FeatureReadings();
        int[] sequence = {0, 3, 1, 2, 5, 4, 7, 6, 9, 8, 2, 5, 1, 4, 0, 3};
        for (int index = 0; index < sequence.length; index++) {
            long nanos = (index + 1) * 1000000000L;
            varied.accelerometer(new float[]{sequence[index], 0, 0}, nanos);
            varied.windows(nanos);
        }
        if (varied.get("permutation_entropy_complexity", 16000000000L).value != 0.92) throw new AssertionError("ordinal distribution entropy");
        FeatureReadings teager = new FeatureReadings();
        int[] signal = {1, 3, 2, 4, 10, 1};
        double[] operator = {Double.NaN, Double.NaN, 7, 0, 0, 10};
        for (int index = 0; index < signal.length; index++) {
            long nanos = (index + 1) * 1000000000L;
            teager.accelerometer(new float[]{signal[index], 0, 0}, nanos);
            teager.windows(nanos);
            FeatureReadings.Reading value = teager.get("non_linear_energy_operator_teager", nanos);
            if (index < 2 ? value != null : value == null || value.value != operator[index]) throw new AssertionError("Teager warmup/formula/clipping");
        }
        teager.windows(7000000000L);
        if (teager.get("non_linear_energy_operator_teager", 7000000000L) != null) throw new AssertionError("held Teager input");
        for (int index = 0; index < 3; index++) {
            long nanos = (index + 8) * 1000000000L;
            teager.accelerometer(new float[]{2, 0, 0}, nanos);
            teager.windows(nanos);
        }
        if (teager.get("non_linear_energy_operator_teager", 10000000000L).value != 0) throw new AssertionError("constant Teager signal");
        teager.accelerometer(new float[]{4, 0, 0}, 17000000000L);
        teager.windows(17000000000L);
        if (teager.get("non_linear_energy_operator_teager", 17000000000L) != null) throw new AssertionError("Teager gap reset");
        FeatureReadings higher = new FeatureReadings();
        for (int index = 0; index < 5; index++) {
            long nanos = (1 + index * 2) * 1000000000L;
            higher.accelerometer(new float[]{index * index * index * index, 0, 0}, nanos);
            if (index < 4 && higher.get("kinematic_pop_metric", nanos) != null) throw new AssertionError("high derivative warmup");
        }
        if (higher.get("kinematic_snap_metric", 9000000000L).value != 27.5
                || higher.get("kinematic_crackle_metric", 9000000000L).value != 7.5
                || higher.get("kinematic_pop_metric", 9000000000L).value != 1.5) throw new AssertionError("successive vector derivatives");
        higher.accelerometer(new float[]{Float.NaN, 0, 0}, 10000000000L);
        if (higher.get("kinematic_pop_metric", 10000000000L) != null) throw new AssertionError("higher derivative invalid reset");
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/FeatureReadings.java"), str(harness)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "org.traceform.edge.ReadingsTest"], check=True, capture_output=True)

    def test_collection_reset_discards_values_and_derivative_history(self):
        if not shutil.which("javac") or not shutil.which("java"):
            self.skipTest("Java compiler/runtime unavailable")
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            harness = Path(directory) / "ResetReadingsTest.java"
            harness.write_text('''package org.traceform.edge;
public final class ResetReadingsTest {
    public static void main(String[] args) {
        FeatureReadings r = new FeatureReadings();
        for (int i=0; i<10; i++) {
            long t=(1+i)*1000000000L;
            r.accelerometer(new float[]{i*i,0,0},t);
            r.gyroscope(new float[]{i,0,0},t);
            r.light(100+i,t); r.pressure(1000+i,t);
            r.electrical(4000+i*10,-100000-i*100000,t);
            r.windows(t);
        }
        r.wifiRssi(-65,10000000000L);
        if(r.get("kinematic_jerk_metric",10000000000L)==null) throw new AssertionError("fixture warmup");
        r.reset();
        for(String name:new String[]{"motion_accel_magnitude","kinematic_jerk_metric","conn_wifi_rssi_norm",
            "env_log_lux","env_pressure_hpa","battery_internal_impedance_miliohm"})
            if(r.get(name,11000000000L)!=null) throw new AssertionError("previous collection value: "+name);
        r.motion(11000000000L); r.windows(11000000000L);
        if(r.get("motion_energy",11000000000L)!=null) throw new AssertionError("motion cache retained");
        r.accelerometer(new float[]{100,0,0},11000000000L);
        r.gyroscope(new float[]{100,0,0},11000000000L);
        r.light(200,11000000000L); r.pressure(1010,11000000000L);
        r.electrical(4100,-1100000,11000000000L); r.windows(11000000000L);
        for(String name:new String[]{"kinematic_jerk_metric","rotational_angular_jerk","kinematic_snap_metric",
            "photometric_contrast_ratio","barometric_vertical_velocity","barometric_turbulence_variance",
            "battery_internal_impedance_miliohm","permutation_entropy_complexity"})
            if(r.get(name,11000000000L)!=null) throw new AssertionError("cross-collection history: "+name);
    }
}''')
            subprocess.run(["javac", "-d", directory, str(root / "android/src/org/traceform/edge/FeatureReadings.java"), str(harness)], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", directory, "org.traceform.edge.ResetReadingsTest"], capture_output=True)
            self.assertEqual(result.returncode, 0, result.stderr.decode())
