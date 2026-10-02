from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


class TestNativeWarmSqlTypes(unittest.TestCase):
    def test_real_sqlite_columns_are_not_truncated_to_integer_binding(self):
        if not shutil.which('javac') or not shutil.which('java'):
            self.skipTest('Java compiler/runtime unavailable')
        root = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            base = Path(directory)
            # Run the production audit against typed cursor rows. Platform and JSON
            # stand-ins isolate SQL type/hash decisions, not Android parsing/recovery.
            sources = {
                'android/os/Build.java': 'package android.os; public final class Build {public static final class VERSION {public static int SDK_INT=35;}}',
                'org/json/JSONException.java': 'package org.json; public class JSONException extends RuntimeException {}',
                'org/json/JSONArray.java': '''package org.json; public class JSONArray { public int length(){return 1;} public String getString(int i){return "A";} public double getDouble(int i){return 0;} }''',
                'org/json/JSONObject.java': '''package org.json;
public class JSONObject {
    public static final Object NULL=new Object();
    java.util.Map<String,Object> data=new java.util.HashMap<>();
    public JSONObject(){}
    public JSONObject(String kind){
        if(kind.equals("model")){data.put("model_sha256","m");data.put("tokens",new JSONArray());}
        else if(kind.equals("frame")) {
            data.put("model_version","v");data.put("model_sha256","m");data.put("normalizer_sha256","n");
            data.put("timestamp_epoch_ms",1000L);data.put("bucket_epoch_ms",0L);data.put("bucket_frame_count",1L);
            data.put("missing_buckets_before",0L);data.put("continuous_from_previous",false);
            data.put("sample_policy","latest_observed_in_utc_30s_bucket");data.put("session_id","s");data.put("source_mode","fixture");
            data.put("state_token","A");data.put("energy_distance",0.0);data.put("state_potential_available",false);
        } else throw new JSONException();
    }
    public JSONObject put(String key,Object value){data.put(key,value);return this;}
    public Object get(String key){if(!data.containsKey(key))throw new JSONException();return data.get(key);}
    public Object opt(String key){return data.get(key);}
    public String getString(String key){return (String)get(key);}
    public long getLong(String key){return ((Number)get(key)).longValue();}
    public double getDouble(String key){return ((Number)get(key)).doubleValue();}
    public boolean getBoolean(String key){return (Boolean)get(key);}
    public JSONArray getJSONArray(String key){return (JSONArray)get(key);}
    public JSONObject getJSONObject(String key){return (JSONObject)get(key);}
    public boolean isNull(String key){return data.get(key)==null || data.get(key)==NULL;}
}''',
                'android/database/Cursor.java': '''package android.database;
public class Cursor implements AutoCloseable {
    public static final int FIELD_TYPE_NULL=0,FIELD_TYPE_INTEGER=1,FIELD_TYPE_FLOAT=2,FIELD_TYPE_STRING=3,FIELD_TYPE_BLOB=4;
    Object[] row;boolean moved;
    public Cursor(Object...row){this.row=row;}
    public boolean moveToNext(){if(moved)return false;moved=true;return true;}
    public long getLong(int i){return ((Number)row[i]).longValue();}
    public double getDouble(int i){return ((Number)row[i]).doubleValue();}
    public byte[] getBlob(int i){return (byte[])row[i];}
    public String getString(int i){return row[i]==null?null:row[i].toString();}
    public int getType(int i){Object v=row[i];return v==null?0:v instanceof Double || v instanceof Float?2:v instanceof Number?1:3;}
    public void close(){}
}''',
                'android/database/sqlite/SQLiteDatabase.java': '''package android.database.sqlite;
public class SQLiteDatabase implements AutoCloseable {
    public Object count=1L,instant=1000L;
    public static SQLiteDatabase create(Object f){return new SQLiteDatabase();}
    public void execSQL(String s,Object... args){}
    public boolean inTransaction(){return false;}
    public void beginTransactionReadOnly(){if(android.os.Build.VERSION.SDK_INT<35)throw new NoSuchMethodError("beginTransactionReadOnly");} public void setTransactionSuccessful(){} public void endTransaction(){} public void close(){}
    public android.database.Cursor rawQuery(String sql,String[] args){
        return sql.contains("cold_models")?new android.database.Cursor("v","n","model"):new android.database.Cursor(0L,instant,"frame",count);
    }
}''',
                'org/traceform/edge/WarmSqlTest.java': '''package org.traceform.edge;
public class WarmSqlTest {
    public static void main(String[]args)throws Exception {
        android.database.sqlite.SQLiteDatabase db=new android.database.sqlite.SQLiteDatabase();
        org.json.JSONObject clean=NativeWarmAudit.run(db);
        if(!clean.getBoolean("valid"))throw new AssertionError("valid integer columns rejected");
        for(boolean timestamp:new boolean[]{false,true}) {
            db.count=1L;db.instant=1000L;
            if(timestamp)db.instant=1000.5;else db.count=1.5;
            org.json.JSONObject audit=NativeWarmAudit.run(db);
            if(audit.getBoolean("valid"))throw new AssertionError("fractional SQL column accepted via getLong");
            if(audit.getString("source_sha256").equals(clean.getString("source_sha256")))throw new AssertionError("raw SQL value change lost in digest");
        }
        for(int api:new int[]{26,34}) {
            android.os.Build.VERSION.SDK_INT=api;
            boolean rejected=false;
            try{NativeWarmAudit.run(db);}catch(UnsupportedOperationException expected){rejected=true;}
            if(!rejected)throw new AssertionError("old Android audit did not reject unsupported read-only transactions");
        }
    }
}
class ModelEngine {
    String modelVersion="v",modelDigest="m",normalizerDigest="n";String[]tokens={"A"},names={"x"};double[]medians={0};
    org.json.JSONObject coldBundle(){return new org.json.JSONObject("model");}
    org.json.JSONObject process(double[]r,double[]v){return new org.json.JSONObject("frame");}
}
class ColdTrajectory {static void initialize(android.database.sqlite.SQLiteDatabase d){} static void register(android.database.sqlite.SQLiteDatabase d,String v,String n,String[]t,org.json.JSONObject o){} }
class WarmBucket {static WarmBucket next(long t,boolean b,WarmBucket p){return new WarmBucket();}}
class WarmTrajectory {static void append(android.database.sqlite.SQLiteDatabase d,org.json.JSONObject o,WarmBucket w){} }
''',
            }
            paths = []
            for name, source in sources.items():
                path = base / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(source)
                paths.append(str(path))
            paths += [str(root / 'android/src/org/traceform/edge' / name) for name in ['NativeWarmAudit.java', 'HexDigest.java']]
            result = subprocess.run(['javac', '-d', directory, *paths], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(['java', '-cp', directory, 'org.traceform.edge.WarmSqlTest'], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
