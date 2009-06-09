/*
 * Copyright (C) 2009 Google Inc.
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

import android.content.ComponentName;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;

/**
 * Holds information about shortcuts (results the user has clicked on before), and returns
 * appropriate shortcuts for a given query.
 */
abstract class ShortcutRepository {

    private static final boolean DBG = false;
    private static final String TAG = ShortcutRepository.class.getSimpleName();


    private static final long DAY_MILLIS = 86400000L;

    /**
     * The maximum age in milliseconds of clicks that will be used for finding
     * and ranking shortcuts.
     *
     * Package visible for testing.
     */
    static final long MAX_STAT_AGE_MILLIS = 7 * DAY_MILLIS;

    static final long MAX_SOURCE_EVENT_AGE_MILLIS = 30 * DAY_MILLIS;

    // these are the baseline to cushion fluctuations in the click through rate ranking of sources.
    // it avoids the case of a new source with 1 click and 1 impression ranking first because it
    // has a 100% CTR.
    private static final int PRIOR_CLICKS = 3;
    private static final int PRIOR_IMPRESSIONS = 30;

    /**
     * Create an instance of the default repository implementation.
     * When the returned object is no longer needed, call {@link #close()}.
     */
    public static ShortcutRepository create(Context context) {
        return ShortcutRepositoryImplLog.create(context);
    }

    /**
     * Gets an instance of a DbOpenHelper or one of its subclasses.
     */
    protected abstract DbOpenHelper getOpenHelper();

    /**
     * Closes any database connections etc held by this object.
     */
    public void close() {
        getOpenHelper().close();
    }

    /**
     * Checks whether there is any stored history.
     */
    public abstract boolean hasHistory();

    /**
     * Clears all shortcut history.
     */
    public void clearHistory() {
        SQLiteDatabase db = getOpenHelper().getWritableDatabase();
        getOpenHelper().clearDatabase(db);
    }

    /**
     * Deletes any database files and other resources used by the repository.
     * This is not necessary to clear the history, and is mostly useful
     * for unit tests.
     */
    public void deleteRepository() {
        getOpenHelper().deleteDatabase();
    }

    /**
     * Used to Report the stats about a completed {@link SuggestionSession}.
     *
     * @param stats The stats.
     */
    public void reportStats(SessionStats stats) {
        reportStats(stats, System.currentTimeMillis());
    }

    abstract void reportStats(SessionStats stats, long now);

    /**
     * @param query The query.
     * @return A list short-cutted results for the query.
     */
    public ArrayList<SuggestionData> getShortcutsForQuery(String query) {
        return getShortcutsForQuery(query, System.currentTimeMillis());
    }

    abstract ArrayList<SuggestionData> getShortcutsForQuery(String query, long now);

    /**
     * @return A ranking of suggestion sources based on clicks and impressions.
     */
    ArrayList<ComponentName> getSourceRanking() {
        return getSourceRanking(PRIOR_CLICKS, PRIOR_IMPRESSIONS);
    }

    abstract ArrayList<ComponentName> getSourceRanking(int priorClicks, int priorImpressions);

    // Creates a string of the form source#intentData#intentAction for use as a unique
    // identifier of a suggestion.
    protected static String makeIntentKey(SuggestionData suggestion) {
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
        return key.toString();
    }


    /**
     * Given a string x, this method returns the least string y such that x is not a prefix of y.
     * This is useful to implement prefix filtering by comparison, since the only strings z that
     * have x as a prefix are such that z is greater than or equal to x and z is less than y.
     *
     * @param str A non-empty string. The contract above is not honored for an empty input string,
     *        since all strings have the empty string as a prefix.
     */
    protected static String nextString(String str) {
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
     * Refreshes a shortcut.
     *
     * @param source Identifies the source of the shortcut.
     * @param shortcutId Identifies the shortcut.
     * @param refreshed An up to date shortcut, or <code>null</code> if the shortcut should be
     *   removed.
     */
    public abstract void refreshShortcut(
            ComponentName source, String shortcutId, SuggestionData refreshed);

    // contains creation and update logic
    protected static abstract class DbOpenHelper extends SQLiteOpenHelper {

        private String mPath;

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

        /**
         * Drops all the database tables.
         */
        public abstract void dropTables(SQLiteDatabase db);

        /**
         * Deletes all data from the database.
         */
        public abstract void clearDatabase(SQLiteDatabase db);

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
    }
}
