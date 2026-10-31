package com.inceptless.traceweave.edge;

import android.os.IBinder;
import android.os.Parcel;
import org.json.JSONObject;

public final class CanonicalClient {
    private CanonicalClient() {}
    /** Read-only modeled clamp comparison; does not authorize or execute actuators. */
    public static JSONObject counterfactual(IBinder service, String timezone, long epochMillis,
            long stepMillis, int horizon, int runs, long seed, int maxDwell, String[] selectedRiskStates) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("com.inceptless.traceweave.edge.Canonical");
            input.writeString(timezone); input.writeLong(epochMillis); input.writeLong(stepMillis);
            input.writeInt(horizon); input.writeInt(runs); input.writeLong(seed);
            input.writeInt(maxDwell); input.writeStringArray(selectedRiskStates);
            if (!service.transact(4, input, output, 0)) throw new IllegalStateException("unsupported counterfactual transaction");
            output.readException();
            return new JSONObject(output.readString());
        } finally { input.recycle(); output.recycle(); }
    }
    public static JSONObject nightDisplayCapabilities(IBinder service) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("com.inceptless.traceweave.edge.Canonical");
            if (!service.transact(3, input, output, 0)) throw new IllegalStateException("unsupported night display transaction");
            output.readException();
            return new JSONObject(output.readString());
        } finally { input.recycle(); output.recycle(); }
    }
    public static JSONObject process(IBinder service, double[] canonicalRaw, double[] normalizedVelocity) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("com.inceptless.traceweave.edge.Canonical");
            input.writeDoubleArray(canonicalRaw); input.writeDoubleArray(normalizedVelocity);
            if (!service.transact(1, input, output, 0)) throw new IllegalStateException("unsupported canonical transaction");
            output.readException();
            return new JSONObject(output.readString());
        } finally { input.recycle(); output.recycle(); }
    }
    public static JSONObject forecast(IBinder service, String timezone, long epochMillis,
            long stepMillis, int horizon, int runs, long seed) throws Exception {
        Parcel input = Parcel.obtain(), output = Parcel.obtain();
        try {
            input.writeInterfaceToken("com.inceptless.traceweave.edge.Canonical");
            input.writeString(timezone); input.writeLong(epochMillis); input.writeLong(stepMillis);
            input.writeInt(horizon); input.writeInt(runs); input.writeLong(seed);
            if (!service.transact(2, input, output, 0)) throw new IllegalStateException("unsupported forecast transaction");
            output.readException();
            return new JSONObject(output.readString());
        } finally { input.recycle(); output.recycle(); }
    }
}
