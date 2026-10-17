package app.littlenest;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class Store extends SQLiteOpenHelper {
    private static Store instance;
    private final android.content.SharedPreferences settings;

    public static synchronized Store get(Context context) {
        if (instance == null) instance = new Store(context.getApplicationContext());
        return instance;
    }

    private Store(Context context) { this(context, "little-nest.db"); }
    Store(Context context, String databaseName) {
        super(context.getApplicationContext(), databaseName, null, 2);
        settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE operations(id TEXT PRIMARY KEY, entity TEXT NOT NULL, clock INTEGER NOT NULL, json TEXT NOT NULL)");
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY, clock INTEGER NOT NULL, op TEXT NOT NULL, json TEXT NOT NULL, deleted INTEGER NOT NULL, entityType TEXT, profileId TEXT, start INTEGER, searchText TEXT)");
        db.execSQL("CREATE TABLE uploaded(folder TEXT NOT NULL, op TEXT NOT NULL, PRIMARY KEY(folder,op))");
        db.execSQL("CREATE TABLE downloaded(folder TEXT NOT NULL, file TEXT NOT NULL, PRIMARY KEY(folder,file))");
        indexes(db);
    }
    @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion == 1 && newVersion == 2) {
            db.execSQL("ALTER TABLE records ADD COLUMN entityType TEXT");
            db.execSQL("ALTER TABLE records ADD COLUMN profileId TEXT");
            db.execSQL("ALTER TABLE records ADD COLUMN start INTEGER");
            db.execSQL("ALTER TABLE records ADD COLUMN searchText TEXT");
            try (Cursor cursor = db.rawQuery("SELECT id,json FROM records", null)) {
                while (cursor.moveToNext()) {
                    ContentValues values = indexed(parse(cursor.getString(1)));
                    db.update("records", values, "id=?", new String[]{cursor.getString(0)});
                }
            }
            indexes(db);
        } else throw new IllegalStateException("No database migration from " + oldVersion + " to " + newVersion);
    }
    private static void indexes(SQLiteDatabase db) {
        db.execSQL("CREATE INDEX records_type ON records(entityType,deleted)");
        db.execSQL("CREATE INDEX records_events ON records(profileId,deleted,start DESC)");
        db.execSQL("CREATE INDEX operations_clock ON operations(clock)");
    }
    private static ContentValues indexed(JSONObject data) {
        ContentValues values = new ContentValues();
        values.put("entityType", data.optString("entity")); values.put("profileId", data.optString("profileId"));
        values.put("start", data.optLong("start"));
        JSONObject search = Records.copy(data); search.remove("photo");
        values.put("searchText", search + (data.optString("entity").equals("event") ? Catalog.get(data.optString("kind")).title() : "")
            + (data.optBoolean("importIncomplete") ? " Needs review" : ""));
        return values;
    }

    public record ImportResult(int added, int skipped, int profilesEnriched) {
        public String message() { return added + " new records imported; " + skipped + " existing records left unchanged; " + profilesEnriched + " profiles filled in."; }
    }
    public synchronized ImportResult importNara(List<NaraImport.Entry> entries) {
        for (NaraImport.Entry entry : entries) NaraImport.operation(entry);
        SQLiteDatabase db = getWritableDatabase();
        int added = 0, skipped = 0, enriched = 0;
        db.beginTransaction();
        try {
            long clock;
            try (Cursor cursor = db.rawQuery("SELECT COALESCE(MAX(clock),0)+1 FROM operations", null)) {
                cursor.moveToFirst(); clock = cursor.getLong(0);
            }
            for (NaraImport.Entry entry : entries) {
                try (Cursor cursor = db.rawQuery("SELECT json,deleted FROM records WHERE id=?", new String[]{entry.id()})) {
                    if (cursor.moveToFirst()) {
                        if (entry.data().optString("entity").equals("profile") && cursor.getInt(1) == 0) {
                            JSONObject current = parse(cursor.getString(0));
                            if (!entry.data().optBoolean("naraUnassigned") && !current.optString("role").equals(entry.data().optString("role")))
                                throw new IllegalArgumentException("The destination profile must have the same Baby / Parent type as the imported profile.");
                            boolean changed = false;
                            for (String key : List.of("dob", "adjustedDob", "sex", "naraProfileKey", "naraSource")) {
                                if (current.optString(key).isBlank() && entry.data().has(key)) {
                                    Records.put(current, key, entry.data().opt(key)); changed = true;
                                }
                            }
                            if (changed) { apply(db, Records.operation(entry.id(), clock++, actor(), current, false)); enriched++; }
                        }
                        skipped++; continue;
                    }
                }
                if (entry.data().optString("entity").equals("event")) {
                    try (Cursor cursor = db.rawQuery("SELECT deleted FROM records WHERE id=?", new String[]{entry.data().optString("profileId")})) {
                        if (!cursor.moveToFirst() || cursor.getInt(0) != 0)
                            throw new IllegalArgumentException("An import profile was deleted. Choose an active destination profile.");
                    }
                    if (entry.data().optBoolean("running")) {
                        for (JSONObject existing : events(entry.data().optString("profileId")))
                            if (existing.optBoolean("running") && existing.optString("kind").equals(entry.data().optString("kind")))
                                throw new IllegalArgumentException("A sleep timer is already running in this profile. Finish it first, or import the unfinished sleep for review instead.");
                    }
                }
                apply(db, NaraImport.operation(entry)); added++;
            }
            db.setTransactionSuccessful();
            return new ImportResult(added, skipped, enriched);
        } finally { db.endTransaction(); }
    }

    public String actor() {
        var prefs = settings;
        String value = prefs.getString("actor", "");
        if (value.isEmpty()) { value = UUID.randomUUID().toString(); prefs.edit().putString("actor", value).apply(); }
        return value;
    }

    public synchronized String save(String id, JSONObject data, boolean deleted) {
        if (id == null) id = UUID.randomUUID().toString();
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            long clock;
            try (Cursor cursor = db.rawQuery("SELECT COALESCE(MAX(clock),0)+1 FROM operations", null)) {
                cursor.moveToFirst(); clock = cursor.getLong(0);
            }
            apply(db, Records.operation(id, clock, actor(), data, deleted));
            db.setTransactionSuccessful();
            return id;
        } finally { db.endTransaction(); }
    }

    public synchronized void merge(List<JSONObject> operations) {
        for (JSONObject operation : operations) Records.validate(operation);
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (JSONObject operation : operations) apply(db, operation);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    private void apply(SQLiteDatabase db, JSONObject operation) {
        String id = operation.optString("opId");
        try (Cursor cursor = db.rawQuery("SELECT json FROM operations WHERE id=?", new String[]{id})) {
            if (cursor.moveToFirst()) {
                if (!parse(cursor.getString(0)).toString().equals(operation.toString()))
                    throw new IllegalArgumentException("Conflicting immutable operation " + id);
                return;
            }
        }
        ContentValues values = new ContentValues();
        values.put("id", id); values.put("entity", operation.optString("entityId"));
        values.put("clock", operation.optLong("clock")); values.put("json", operation.toString());
        db.insertOrThrow("operations", null, values);
        JSONObject prior = null;
        try (Cursor cursor = db.rawQuery("SELECT op FROM records WHERE id=?", new String[]{operation.optString("entityId")})) {
            if (cursor.moveToFirst()) prior = parse(cursor.getString(0));
        }
        if (prior == null || Records.compare(operation, prior) > 0) {
            ContentValues record = new ContentValues();
            record.put("id", operation.optString("entityId")); record.put("clock", operation.optLong("clock"));
            record.put("op", operation.toString()); record.put("json", operation.optJSONObject("data").toString());
            record.put("deleted", operation.optBoolean("deleted") ? 1 : 0);
            record.putAll(indexed(operation.optJSONObject("data")));
            db.insertWithOnConflict("records", null, record, SQLiteDatabase.CONFLICT_REPLACE);
        }
    }

    public synchronized List<JSONObject> records(String entity) {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,json FROM records WHERE deleted=0 AND entityType=? ORDER BY clock DESC,id", new String[]{entity})) {
            while (cursor.moveToNext()) {
                JSONObject data = parse(cursor.getString(1));
                if (data.optString("entity").equals(entity)) {
                    Records.put(data, "id", cursor.getString(0)); result.add(data);
                }
            }
        }
        return result;
    }

    public synchronized List<JSONObject> events(String profileId) {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT id,json FROM records WHERE profileId=? AND deleted=0 AND entityType='event' ORDER BY start DESC,id", new String[]{profileId})) {
            while (cursor.moveToNext()) {
                JSONObject data = parse(cursor.getString(1)); Records.put(data, "id", cursor.getString(0)); result.add(data);
            }
        }
        return result;
    }

    public synchronized List<JSONObject> eventPage(String profileId, String search, long from, long to, int offset) {
        List<JSONObject> result = new ArrayList<>();
        String pattern = "%" + search.toLowerCase(java.util.Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        String query = "SELECT id,json FROM records WHERE profileId=? AND deleted=0 AND entityType='event' AND start>=? AND start<?"
            + " AND LOWER(searchText) LIKE ? ESCAPE '\\' ORDER BY start DESC,id LIMIT 101 OFFSET ?";
        try (Cursor cursor = getReadableDatabase().rawQuery(query, new String[]{profileId, Long.toString(from), Long.toString(to), pattern, Integer.toString(offset)})) {
            while (cursor.moveToNext()) {
                JSONObject data = parse(cursor.getString(1)); Records.put(data, "id", cursor.getString(0)); result.add(data);
            }
        }
        return result;
    }

    public synchronized List<JSONObject> operations() {
        List<JSONObject> result = new ArrayList<>();
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT json FROM operations ORDER BY clock,id", null)) {
            while (cursor.moveToNext()) result.add(parse(cursor.getString(0)));
        }
        return result;
    }

    public synchronized boolean marked(String table, String folder, String id) {
        String column = table.equals("uploaded") ? "op" : "file";
        if (!table.equals("uploaded") && !table.equals("downloaded")) throw new IllegalArgumentException("Invalid sync table");
        try (Cursor cursor = getReadableDatabase().rawQuery("SELECT 1 FROM " + table + " WHERE folder=? AND " + column + "=?", new String[]{folder, id})) {
            return cursor.moveToFirst();
        }
    }

    public synchronized void mark(String table, String folder, String id) {
        if (!table.equals("uploaded") && !table.equals("downloaded")) throw new IllegalArgumentException("Invalid sync table");
        ContentValues values = new ContentValues();
        values.put("folder", folder); values.put(table.equals("uploaded") ? "op" : "file", id);
        getWritableDatabase().insertWithOnConflict(table, null, values, SQLiteDatabase.CONFLICT_IGNORE);
    }

    public String backup() {
        JSONObject root = new JSONObject();
        Records.put(root, "schema", Records.SCHEMA);
        Records.put(root, "operations", new JSONArray(operations()));
        return root.toString();
    }

    static JSONObject parse(String json) {
        try { return new JSONObject(json); }
        catch (JSONException e) { throw new IllegalStateException("Stored data is corrupt. Restore a backup before continuing.", e); }
    }
}
