/*
 * Copyright (C) The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.globalsearch;

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteQueryBuilder;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.Arrays;
import java.io.File;

/**
 * A shortcut repository implementation that uses a log of every click.
 * 
 */
class ShortcutRepositoryImplLog implements ShortcutRepository {

    private static final boolean DBG = false;
    private static final String TAG = "GlobalSearch";

    private static final String DB_NAME = "shortcuts-log.db";
    private static final int DB_VERSION = 18;

    private static final String HAS_HISTORY_QUERY =
        "SELECT " + Shortcuts.intent_key.fullName + " FROM " + Shortcuts.TABLE_NAME;
    private static final String EMPTY_QUERY_SHORTCUT_QUERY = buildShortcutQuery(true);
    private static final String SHORTCUT_QUERY = buildShortcutQuery(false);

    private static final String SHORTCUT_UPDATE_SQL = buildUpdateShortcutSql();
    private static final String SHORTCUT_DELETE_SQL = buildDeleteShortcutSql();

    private static final String SOURCE_RANKING_SQL = buildSourceRankingSql();

    private DbOpenHelper mOpenHelper;

    /**
     * Create an instance to the repo.
     */
    public static ShortcutRepository create(Context context) {
        return new ShortcutRepositoryImplLog(context, DB_NAME);
    }

    /**
     * @return A String with the sql to delete a shortcut based on its shortcut id.  The sql uses
     *   query parameters.
     */
    static String buildDeleteShortcutSql() {
        final StringBuilder sb = new StringBuilder(100);
        sb.append("DELETE FROM ").append(Shortcuts.TABLE_NAME).append(" ");
        sb.append("WHERE ");
        sb.append(Shortcuts.shortcut_id.name()).append("=$1");
        sb.append(" AND ").append(Shortcuts.source.name()).append("=?2");
        return sb.toString();
    }

    /**
     * @return A String with the sql to update a shortcut based on its shortcut id.  The sql uses
     *   query paremeters.
     */
    static String buildUpdateShortcutSql() {
        final StringBuilder sb = new StringBuilder(100);
        sb.append("UPDATE ").append(Shortcuts.TABLE_NAME).append(" ");
        sb.append("SET ");
        sb.append(Shortcuts.format.name()).append("=?1");
        sb.append(", ");
        sb.append(Shortcuts.title.name()).append("=?2");
        sb.append(", ");
        sb.append(Shortcuts.description.name()).append("=?3");
        sb.append(", ");
        sb.append(Shortcuts.icon1.name()).append("=?4");
        sb.append(", ");
        sb.append(Shortcuts.icon2.name()).append("=?5");
        sb.append(" WHERE ")
                .append(Shortcuts.shortcut_id.name())
                .append("=?6")
                .append(" AND ").append(Shortcuts.source.name()).append("=?7");
        return sb.toString();
    }

    private static String buildShortcutQuery(boolean emptyQuery) {
        // clicklog first, since that's where restrict the result set
        String tables = ClickLog.TABLE_NAME + " INNER JOIN " + Shortcuts.TABLE_NAME
                + " ON " + ClickLog.intent_key.fullName + " = " + Shortcuts.intent_key.fullName;
        String[] columns = Shortcuts.COLUMNS;
        // SQL expression for the time before which no clicks should be counted.
        String cutOffTime_expr = "(" + "?3" + " - " + MAX_STAT_AGE_MILLIS + ")";
        // Avoid GLOB by using >= AND <, with some manipulation (see nextString(String)).
        // to figure out the upper bound (e.g. >= "abc" AND < "abd"
        // This allows us to use parameter binding and still take advantage of the
        // index on the query column.
        String prefixRestriction =
                ClickLog.query.fullName + " >= ?1 AND " + ClickLog.query.fullName + " < ?2";
        // Filter out clicks that are too old
        String ageRestriction = ClickLog.hit_time.fullName + " >= " + cutOffTime_expr;
        String where = (emptyQuery ? "" : prefixRestriction + " AND ") + ageRestriction;
        String groupBy = ClickLog.intent_key.fullName;
        String having = null;
        String hit_count_expr = "COUNT(" + ClickLog._id.fullName + ")";
        String last_hit_time_expr = "MAX(" + ClickLog.hit_time.fullName + ")";
        String scale_expr =
            // time (msec) from cut-off to last hit time
            "((" + last_hit_time_expr + " - " + cutOffTime_expr + ") / "
            // divided by time (sec) from cut-off to now
            // we use msec/sec to get 1000 as max score
            + (MAX_STAT_AGE_MILLIS / 1000) + ")";
        String ordering_expr = "(" + hit_count_expr + " * " + scale_expr + ")";
        String orderBy = ordering_expr + " DESC";
        // TODO: getShortcutsForQuery() should have a maxCount argument that we
        // could use in the LIMIT clause
        String limit = null;
        return SQLiteQueryBuilder.buildQueryString(
                false, tables, columns, where, groupBy, having, orderBy, limit);
    }

    /**
     * @return sql that ranks sources by click through rate, filtering out sources without enough
     *         impressions.
     */
    private static String buildSourceRankingSql() {
        final String orderingExpr = "1000*" + SourceStats.total_clicks.name() +
                "/" + SourceStats.total_impressions.name();

        final String tables = SourceStats.TABLE_NAME;
        final String[] columns = SourceStats.COLUMNS;
        final String where = SourceStats.total_impressions + " >= $1";
        final String groupBy = null;
        final String having = null;
        final String orderBy = orderingExpr + " DESC";
        final String limit = null;
        return SQLiteQueryBuilder.buildQueryString(
                false, tables, columns, where, groupBy, having, orderBy, limit);
    }

    /**
     * @param context Used to create / open db
     * @param name The name of the database to create.
     */
    ShortcutRepositoryImplLog(Context context, String name) {
        mOpenHelper = new DbOpenHelper(context, name, DB_VERSION);
    }

    protected DbOpenHelper getOpenHelper() {
        return mOpenHelper;
    }

// --------------------- Interface ShortcutRepository ---------------------

    /** {@inheritDoc} */
    public boolean hasHistory() {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(HAS_HISTORY_QUERY, null);
        try {
            if (DBG) Log.d(TAG, "hasHistory(): cursor=" + cursor);
            return cursor != null && cursor.getCount() > 0;
        } finally {
            if (cursor != null) cursor.close();
        }
    }

    /** {@inheritDoc} */
    public void clearHistory() {
        SQLiteDatabase db = getOpenHelper().getWritableDatabase();
        getOpenHelper().clearDatabase(db);
    }

    /** {@inheritDoc} */
    public void deleteRepository() {
        getOpenHelper().deleteDatabase();
    }

    /** {@inheritDoc} */
    public void close() {
        getOpenHelper().close();
    }

    /** {@inheritDoc} */
    public void reportStats(SessionStats stats) {
        reportStats(stats, System.currentTimeMillis());
    }

    /** {@inheritDoc} */
    public ArrayList<SuggestionData> getShortcutsForQuery(String query) {
        return getShortcutsForQuery(query, System.currentTimeMillis());
    }

    /** {@inheritDoc} */
    public ArrayList<ComponentName> getSourceRanking() {
        return getSourceRanking(MIN_IMPRESSIONS_FOR_SOURCE_RANKING);
    }

    /** {@inheritDoc} */
    public void refreshShortcut(ComponentName source, String shortcutId, SuggestionData refreshed) {
        if (source == null) throw new NullPointerException("source");
        if (shortcutId == null) throw new NullPointerException("shortcutId");

        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        if (refreshed == null) {
            db.execSQL(
                    SHORTCUT_DELETE_SQL,
                    new Object[] {shortcutId, source.flattenToShortString()});
        } else {
            // Store the spinner icon as the icon2 if we need to show one whenever this
            // shortcut gets displayed. We always store the spinner as icon2 for these cases,
            // but the shortcut refresh query will update the in-memory results, thereby
            // removing the spinner when the refresh is complete.
            final String icon2 = refreshed.isSpinnerWhileRefreshing() ?
                    String.valueOf(com.android.internal.R.drawable.search_spinner) :
                    refreshed.getIcon2();
            db.execSQL(
                    SHORTCUT_UPDATE_SQL,
                    new Object[]{
                            refreshed.getFormat(),         // ?1
                            refreshed.getTitle(),          // ?2
                            refreshed.getDescription(),    // ?3
                            refreshed.getIcon1(),          // ?4
                            icon2,                         // ?5
                            shortcutId,                    // ?6
                            source.flattenToShortString(), // ?7
                    });
        }
    }

// -------------------------- end ShortcutRepository --------------------------

    ArrayList<SuggestionData> getShortcutsForQuery(String query, long now) {
        String sql = query.length() == 0 ? EMPTY_QUERY_SHORTCUT_QUERY : SHORTCUT_QUERY;
        String[] params = buildShortcutQueryParams(query, now);
        if (DBG) {
            Log.d(TAG, sql);
            Log.d(TAG, Arrays.toString(params));
        }

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        Cursor cursor = db.rawQuery(sql, params);

        try {
            ArrayList<SuggestionData> shortcuts = new ArrayList<SuggestionData>(cursor.getCount());
            while (cursor.moveToNext()) {
                SuggestionData suggestionData = suggestionFromCursor(cursor);
                if (DBG) Log.d(TAG, "cursor to data: " + suggestionData);
                shortcuts.add(suggestionData);
            }
            return shortcuts;
        } finally {
            cursor.close();
        }
    }

    /**
     * Builds a parameter list for the query returned by {@link #buildShortcutQuery(boolean)}.
     */
    private static String[] buildShortcutQueryParams(String query, long now) {
        return new String[]{ query, nextString(query), String.valueOf(now) };
    }

    private SuggestionData suggestionFromCursor(Cursor cursor) {
        ComponentName sourceName =
            ComponentName.unflattenFromString(cursor.getString(Shortcuts.source.ordinal()));
        return new SuggestionData.Builder(sourceName)
            .format(cursor.getString(Shortcuts.format.ordinal()))
            .title(cursor.getString(Shortcuts.title.ordinal()))
            .description(cursor.getString(Shortcuts.description.ordinal()))
            .icon1(cursor.getString(Shortcuts.icon1.ordinal()))
            .icon2(cursor.getString(Shortcuts.icon2.ordinal()))
            .intentAction(cursor.getString(Shortcuts.intent_action.ordinal()))
            .intentData(cursor.getString(Shortcuts.intent_data.ordinal()))
            .intentQuery(cursor.getString(Shortcuts.intent_query.ordinal()))
            .intentExtraData(cursor.getString(Shortcuts.intent_extradata.ordinal()))
            .intentComponentName(cursor.getString(Shortcuts.intent_component_name.ordinal()))
            .shortcutId(cursor.getString(Shortcuts.shortcut_id.ordinal()))
            .build();
    }

    /**
     * Given a string x, this method returns the least string y such that x is not a prefix of y.
     * This is useful to implement prefix filtering by comparison, since the only strings z that
     * have x as a prefix are such that z is greater than or equal to x and z is less than y.
     *
     * @param str A non-empty string. The contract above is not honored for an empty input string,
     *        since all strings have the empty string as a prefix.
     */
    private static String nextString(String str) {
        int len = str.length();
        if (len == 0) {
            return str;
        }
        // The last code point in the string. Within the Basic Multilingual Plane,
        // this is the same as str.charAt(len-1)
        int codePoint = str.codePointBefore(len);
        // This should be safe from overflow, since the largest code point
        // representable in UTF-16 is U+10FFFF.
        int nextCodePoint = codePoint + 1;
        // The index of the start of the last code point.
        // Character.charCount(codePoint) is always 1 (in the BMP) or 2
        int lastIndex = len - Character.charCount(codePoint);
        return new StringBuilder(len)
                .append(str, 0, lastIndex)  // append everything but the last code point
                .appendCodePoint(nextCodePoint)  // instead of the last code point, use successor
                .toString();
    }

    /**
     * Returns the source ranking for sources with a minimum number of impressions.
     *
     * @param minImpressions The minimum number of impressions a source must have.
     * @return The list of sources, ranked by click through rate.
     */
    ArrayList<ComponentName> getSourceRanking(int minImpressions) {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        final Cursor cursor = db.rawQuery(
                SOURCE_RANKING_SQL,
                new String[] { String.valueOf(minImpressions) });
        try {
            final ArrayList<ComponentName> sources =
                    new ArrayList<ComponentName>(cursor.getCount());
            while (cursor.moveToNext()) {
                sources.add(sourceFromCursor(cursor));
            }
            return sources;
        } finally {
            cursor.close();
        }
    }

    private ComponentName sourceFromCursor(Cursor cursor) {
        return ComponentName.unflattenFromString(cursor.getString(SourceStats.component.ordinal()));
    }

    /**
     * Reports the session stats for a particular time.
     *
     * @param stats The stats.
     * @param now Millis since epoch.
     */
    void reportStats(SessionStats stats, long now) {
        logClicked(stats, now);
        logSourceEvents(stats, now);
        postSourceEventCleanup(now);
    }

    private void logClicked(SessionStats stats, long now) {
        final SuggestionData clicked = stats.getClicked();
        if (clicked == null) {
            if (DBG) Log.d(TAG, "logClicked: nothing to log");
            return;
        }

        if (DBG) {
            Log.d(TAG, "logClicked(" + stats.getClicked().getIntentAction() + ", " +
                    "\"" + stats.getQuery() + "\")");
        }

        if (SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT.equals(clicked.getShortcutId())) {
            if (DBG) Log.d(TAG, "clicked suggestion requested not to be shortcuted");
            return;
        }

        String intentKey = makeIntentKey(clicked);

        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        // Add or update suggestion info
        // Since intent_key is the primary key, any existing
        // suggestion with the same source+data+action will be replaced
        {
            // Store the spinner icon as the icon2 if we need to show one whenever this
            // shortcut gets displayed. We always store the spinner as icon2 for these cases,
            // but the shortcut refresh query will update the in-memory results, thereby
            // removing the spinner when the refresh is complete.
            String icon2 = clicked.isSpinnerWhileRefreshing() ?
                    String.valueOf(com.android.internal.R.drawable.search_spinner) :
                    clicked.getIcon2();
            final ContentValues cv = new ContentValues();
            cv.put(Shortcuts.intent_key.name(), intentKey);
            cv.put(Shortcuts.source.name(), clicked.getSource().flattenToShortString());
            cv.put(Shortcuts.format.name(), clicked.getFormat());
            cv.put(Shortcuts.title.name(), clicked.getTitle());
            cv.put(Shortcuts.description.name(), clicked.getDescription());
            cv.put(Shortcuts.icon1.name(), clicked.getIcon1());
            cv.put(Shortcuts.icon2.name(), icon2);
            cv.put(Shortcuts.intent_action.name(), clicked.getIntentAction());
            cv.put(Shortcuts.intent_data.name(), clicked.getIntentData());
            cv.put(Shortcuts.intent_query.name(), clicked.getIntentQuery());
            cv.put(Shortcuts.intent_extradata.name(), clicked.getIntentExtraData());
            cv.put(Shortcuts.intent_component_name.name(), clicked.getIntentComponentName());
            cv.put(Shortcuts.shortcut_id.name(), clicked.getShortcutId());
            cv.put(Shortcuts.spinner_while_refreshing.name(), clicked.isSpinnerWhileRefreshing());
            db.replaceOrThrow(Shortcuts.TABLE_NAME, null, cv);
        }

        // Log click
        {
            final ContentValues cv = new ContentValues();
            cv.put(ClickLog.intent_key.name(), intentKey);
            cv.put(ClickLog.query.name(), stats.getQuery());
            cv.put(ClickLog.hit_time.name(), now);
            db.insertOrThrow(ClickLog.TABLE_NAME, null, cv);
        }
    }

    private void logSourceEvents(SessionStats stats, long now) {
        final SuggestionData clicked = stats.getClicked();
        final SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        db.beginTransaction();
        final ContentValues cv = new ContentValues();
        try {
            for (ComponentName name : stats.getSourceImpressions()) {
                final int clickCount = clicked != null && clicked.getSource().equals(name) ?
                        1 : 0;

                if (DBG) {
                    Log.d(TAG, "inserting " + name.toShortString()
                            + " clicks=" + clickCount + ", impressions=" + 1);
                }

                cv.put(SourceLog.component.name(), name.flattenToString());
                cv.put(SourceLog.time.name(), now);
                cv.put(SourceLog.click_count.name(), clickCount);
                cv.put(SourceLog.impression_count.name(), 1);
                db.insertOrThrow(SourceLog.TABLE_NAME, null, cv);

                cv.clear();
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /**
     * Execute queries necessary to keep things up to date after inserting into {@link SourceLog}.
     *
     * Note: we aren't using a TRIGGER because there are usually several writes to the log at a
     * time, and triggers execute on each individual row insert.
     *
     * @param now Millis since epoch of "now".
     */
    private void postSourceEventCleanup(long now) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        // purge old log entries
        db.execSQL("DELETE FROM " + SourceLog.TABLE_NAME + " WHERE "
                + SourceLog.time.name() + " <"
                + now + " - " + MAX_SOURCE_EVENT_AGE_MILLIS + ";");

        // update the source stats
        final String columns = SourceLog.component + "," +
                "SUM(" + SourceLog.click_count.fullName + ")" + "," +
                "SUM(" + SourceLog.impression_count.fullName + ")";
        db.execSQL("DELETE FROM " + SourceStats.TABLE_NAME);
        db.execSQL("INSERT INTO " + SourceStats.TABLE_NAME  + " "
                + "SELECT " + columns + " FROM " + SourceLog.TABLE_NAME + " GROUP BY "
                + SourceLog.component.name());
    }

    // Creates a string of the form source#intentData#intentAction#intentQuery 
    // for use as a unique identifier of a suggestion.
    private static String makeIntentKey(SuggestionData suggestion) {
        ComponentName source = suggestion.getSource();
        String intentAction = suggestion.getIntentAction();
        String intentData = suggestion.getIntentData();
        StringBuilder key = new StringBuilder(source.flattenToShortString());
        key.append("#");
        if (intentData != null) {
            key.append(intentData);
        }
        key.append("#");
        if (intentAction != null) {
            key.append(intentAction);
        }
        key.append("#");
        if (suggestion.getIntentQuery() != null) {
            key.append(suggestion.getIntentQuery());
        }
        return key.toString();
    }

// -------------------------- TABLES --------------------------

    /**
     * shortcuts table
     */
    enum Shortcuts {
        intent_key,
        source,
        format,
        title,
        description,
        icon1,
        icon2,
        intent_action,
        intent_data,
        intent_query,
        intent_extradata,
        intent_component_name,
        shortcut_id,
        // Note: deliberately omitting background color and pin-to-bottom values since they
        // are only used for suggestions which cannot be shortcutted (namely, "more results"
        // and "manage search history" respectively).
        spinner_while_refreshing;

        static final String[] COLUMNS = initColumns();

        static final String TABLE_NAME = "shortcuts";

        private static String[] initColumns() {
            Shortcuts[] vals = Shortcuts.values();
            String[] columns = new String[vals.length];
            for (int i = 0; i < vals.length; i++) {
                columns[i] = vals[i].fullName;
            }
            return columns;
        }

        public final String fullName;

        Shortcuts() {
            fullName = TABLE_NAME + "." + name();
        }
    }

    /**
     * clicklog table. Has one record for each click.
     */
    enum ClickLog {
        _id,
        intent_key,
        query,
        hit_time;

        static final String[] COLUMNS = initColumns();

        static final String TABLE_NAME = "clicklog";

        private static String[] initColumns() {
            ClickLog[] vals = ClickLog.values();
            String[] columns = new String[vals.length];
            for (int i = 0; i < vals.length; i++) {
                columns[i] = vals[i].fullName;
            }
            return columns;
        }

        public final String fullName;

        ClickLog() {
            fullName = TABLE_NAME + "." + name();
        }
    }

    /**
     * We store stats about clicks and impressions per source to facilitate the ranking of
     * the sources, and which are promoted vs under the "more results" entry.
     */
    enum SourceLog {
        _id,
        component,
        time,
        click_count,
        impression_count;

        static final String[] COLUMNS = initColumns();

        static final String TABLE_NAME = "sourceeventlog";

        private static String[] initColumns() {
            SourceLog[] vals = SourceLog.values();
            String[] columns = new String[vals.length];
            for (int i = 0; i < vals.length; i++) {
                columns[i] = vals[i].fullName;
            }
            return columns;
        }

        public final String fullName;

        SourceLog() {
            fullName = TABLE_NAME + "." + name();
        }
    }

    /**
     * This is an aggregate table of {@link SourceLog} that stays up to date with the total
     * clicks and impressions for each source.  This makes computing the source ranking more
     * more efficient, at the expense of some extra work when the source clicks and impressions
     * are reported at the end of the session.
     */
    enum SourceStats {
        component,
        total_clicks,
        total_impressions;

        static final String TABLE_NAME = "sourcetotals";

        static final String[] COLUMNS = initColumns();

        private static String[] initColumns() {
            SourceStats[] vals = SourceStats.values();
            String[] columns = new String[vals.length];
            for (int i = 0; i < vals.length; i++) {
                columns[i] = vals[i].fullName;
            }
            return columns;
        }

        public final String fullName;

        SourceStats() {
            fullName = TABLE_NAME + "." + name();
        }
    }

// -------------------------- END TABLES --------------------------

    // contains creation and update logic
    private static class DbOpenHelper extends SQLiteOpenHelper {
        private String mPath;
        private static final String CLICKLOG_QUERY_INDEX
                = ClickLog.TABLE_NAME + "_" + ClickLog.query.name();
        private static final String CLICKLOG_HIT_TIME_INDEX
                = ClickLog.TABLE_NAME + "_" + ClickLog.hit_time.name();
        private static final String CLICKLOG_PURGE_TRIGGER
                = ClickLog.TABLE_NAME + "_purge";
        private static final String SHORTCUTS_DELETE_TRIGGER
                = Shortcuts.TABLE_NAME + "_delete";

        public DbOpenHelper(Context context, String name, int version) {
            super(context, name, null, version);
        }

        public String getPath() {
            return mPath;
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // The shortcuts info is not all that important, so we just drop the tables
            // and re-create empty ones.
            Log.i(TAG, "Upgrading shortcuts DB from version " +
                    + oldVersion + " to " + newVersion + ". This deletes all shortcuts.");
            dropTables(db);
            onCreate(db);
        }

        private void dropTables(SQLiteDatabase db) {
            db.execSQL("DROP TRIGGER IF EXISTS " + CLICKLOG_PURGE_TRIGGER);
            db.execSQL("DROP TRIGGER IF EXISTS " + SHORTCUTS_DELETE_TRIGGER);
            db.execSQL("DROP INDEX IF EXISTS " + CLICKLOG_HIT_TIME_INDEX);
            db.execSQL("DROP INDEX IF EXISTS " + CLICKLOG_QUERY_INDEX);
            db.execSQL("DROP TABLE IF EXISTS " + ClickLog.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + Shortcuts.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + SourceLog.TABLE_NAME);
            db.execSQL("DROP TABLE IF EXISTS " + SourceStats.TABLE_NAME);
        }

        private void clearDatabase(SQLiteDatabase db) {
            db.delete(ClickLog.TABLE_NAME, null, null);
            db.delete(Shortcuts.TABLE_NAME, null, null);
            db.delete(SourceLog.TABLE_NAME, null, null);
            db.delete(SourceStats.TABLE_NAME, null, null);
        }

        /**
         * Deletes the database file.
         */
        public void deleteDatabase() {
            close();
            if (mPath == null) return;
            try {
                new File(mPath).delete();
                if (DBG) Log.d(TAG, "deleted " + mPath);
            } catch (Exception e) {
                Log.w(TAG, "couldn't delete " + mPath, e);
            }
        }

        @Override
        public void onOpen(SQLiteDatabase db) {
            super.onOpen(db);
            mPath = db.getPath();
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + Shortcuts.TABLE_NAME + " (" +
                    // COLLATE UNICODE is needed to make it possible to use nextString()
                    // to implement fast prefix filtering.
                    Shortcuts.intent_key.name() + " TEXT NOT NULL COLLATE UNICODE PRIMARY KEY, " +
                    Shortcuts.source.name() + " TEXT NOT NULL, " +
                    Shortcuts.format.name() + " TEXT, " +
                    Shortcuts.title.name() + " TEXT, " +
                    Shortcuts.description.name() + " TEXT, " +
                    Shortcuts.icon1.name() + " TEXT, " +
                    Shortcuts.icon2.name() + " TEXT, " +
                    Shortcuts.intent_action.name() + " TEXT, " +
                    Shortcuts.intent_data.name() + " TEXT, " +
                    Shortcuts.intent_query.name() + " TEXT, " +
                    Shortcuts.intent_extradata.name() + " TEXT, " +
                    Shortcuts.intent_component_name.name() + " TEXT, " +
                    Shortcuts.shortcut_id.name() + " TEXT, " +
                    Shortcuts.spinner_while_refreshing.name() + " TEXT" +
                    ");");

            db.execSQL("CREATE TABLE " + ClickLog.TABLE_NAME + " ( " +
                    ClickLog._id.name() + " INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    // type must match Shortcuts.intent_key
                    ClickLog.intent_key.name() + " TEXT NOT NULL COLLATE UNICODE REFERENCES "
                        + Shortcuts.TABLE_NAME + "(" + Shortcuts.intent_key + "), " +
                    ClickLog.query.name() + " TEXT, " +
                    ClickLog.hit_time.name() + " INTEGER" +
                    ");");

            // index for fast lookup of clicks by query
            db.execSQL("CREATE INDEX " + CLICKLOG_QUERY_INDEX
                    + " ON " + ClickLog.TABLE_NAME + "(" + ClickLog.query.name() + ")");

            // index for finding old clicks quickly
            db.execSQL("CREATE INDEX " + CLICKLOG_HIT_TIME_INDEX
                    + " ON " + ClickLog.TABLE_NAME + "(" + ClickLog.hit_time.name() + ")");

            // trigger for purging old clicks, i.e. those such that
            // hit_time < now - MAX_MAX_STAT_AGE_MILLIS, where now is the
            // hit_time of the inserted record
            db.execSQL("CREATE TRIGGER " + CLICKLOG_PURGE_TRIGGER + " AFTER INSERT ON "
                    + ClickLog.TABLE_NAME
                    + " BEGIN"
                    + " DELETE FROM " + ClickLog.TABLE_NAME + " WHERE "
                            + ClickLog.hit_time.name() + " <"
                            + " NEW." + ClickLog.hit_time.name()
                                    + " - " + MAX_STAT_AGE_MILLIS + ";"
                    + " END");

            // trigger for deleting clicks about a shortcut once that shortcut has been
            // deleted
            db.execSQL("CREATE TRIGGER " + SHORTCUTS_DELETE_TRIGGER + " AFTER DELETE ON "
                    + Shortcuts.TABLE_NAME
                    + " BEGIN"
                    + " DELETE FROM " + ClickLog.TABLE_NAME + " WHERE "
                            + ClickLog.intent_key.name()
                            + " = OLD." + Shortcuts.intent_key.name() + ";"
                    + " END");

            db.execSQL("CREATE TABLE " + SourceLog.TABLE_NAME + " ( " +
                    SourceLog._id.name() + " INTEGER NOT NULL PRIMARY KEY AUTOINCREMENT, " +
                    SourceLog.component.name() + " TEXT NOT NULL COLLATE UNICODE, " +
                    SourceLog.time.name() + " INTEGER, " +
                    SourceLog.click_count + " INTEGER, " +
                    SourceLog.impression_count + " INTEGER);"
            );

            db.execSQL("CREATE TABLE " + SourceStats.TABLE_NAME + " ( " +
                    SourceStats.component.name() + " TEXT NOT NULL COLLATE UNICODE PRIMARY KEY, " +
                    SourceStats.total_clicks + " INTEGER, " +
                    SourceStats.total_impressions + " INTEGER);"
                    );
        }
    }
}

/*
      query building template
        final String tables = "";
        final String[] columns = {};
        final String where = "";
        final String groupBy = "";
        final String having = "";
        final String orderBy = "";
        final String limit = "";
        SQLiteQueryBuilder.buildQueryString(
                false, tables, columns, where, groupBy, having, orderBy, limit);
     */

