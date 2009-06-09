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

package com.android.globalsearch.benchmarks;

import android.app.Activity;
import android.app.SearchManager;
import android.content.ComponentName;
import android.database.ContentObserver;
import android.database.Cursor;
import android.database.DataSetObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.server.search.SearchableInfo;
import android.util.Log;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Latency tests for suggestion sources.
 */

/*

To build and run:

mmm vendor/google/apps/GlobalSearch/benchmarks \
&& adb -e install -r $OUT/system/app/GlobalSearchBenchmarks.apk \
&& sleep 10 \
&& adb -e shell am start -a android.intent.action.MAIN \
        -n com.android.globalsearch.benchmarks/.SourceLatency \
&& adb -e logcat

 */

public class SourceLatency extends Activity {

    private static final String TAG = "SourceLatency";

    private ExecutorService mExecutorService;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mExecutorService = Executors.newSingleThreadExecutor();
    }

    @Override
    protected void onResume() {
        super.onResume();

        // TODO: call finish() when all tasks are done
    }

    public double checkSource(String src, ComponentName componentName, String[] queries) {
        double totalMs = 0.0;
        int count = queries.length;
        for (int i = 0; i < queries.length; i++) {
            totalMs += checkSource(src, componentName, queries[i]);
        }
        double average = totalMs / count;
        Log.d(TAG, src + "[DONE]: " + count + " queries in " + average + " ms (average), "
                + totalMs + " ms (total)");
        return average;
    }

    public double checkSource(String src, ComponentName componentName, String query) {
        SearchableInfo searchable = SearchManager.getSearchableInfo(componentName, false);
        if (searchable == null || searchable.getSuggestAuthority() == null) {
            throw new RuntimeException("Component is not searchable: "
                    + componentName.flattenToShortString());
        }
        Cursor cursor = null;
        try {
            final long start = System.nanoTime();
            cursor = SearchManager.getSuggestions(SourceLatency.this, searchable, query);
            long end = System.nanoTime();
            double elapsedMs = (end - start) / 1000000.0d;
            if (cursor == null) {
                Log.d(TAG, src + ": null cursor in " + elapsedMs
                        + " ms for '" + query + "'");
            } else {
                Log.d(TAG, src + ": " + cursor.getCount() + " rows in " + elapsedMs
                        + " ms for '" + query + "'");
            }
            return elapsedMs;
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    public void checkLiveSource(String src, ComponentName componentName, String query) {
        mExecutorService.submit(new LiveSourceCheck(src, componentName, query));
    }

    private class LiveSourceCheck implements Runnable {

        private String mSrc;
        private SearchableInfo mSearchable;
        private String mQuery;
        private Handler mHandler = new Handler(Looper.getMainLooper());

        public LiveSourceCheck(String src, ComponentName componentName, String query) {
            mSrc = src;
            mSearchable = SearchManager.getSearchableInfo(componentName, false);
            assert(mSearchable != null);
            assert(mSearchable.getSuggestAuthority() != null);
            mQuery = query;
        }

        public void run() {
            Cursor cursor = null;
            try {
                final long start = System.nanoTime();
                cursor = SearchManager.getSuggestions(SourceLatency.this, mSearchable, mQuery);
                long end = System.nanoTime();
                double elapsedMs = (end - start) / 1000000.0d;
                if (cursor == null) {
                    Log.d(TAG, mSrc + ": null cursor in " + elapsedMs
                            + " ms for '" + mQuery + "'");
                } else {
                    Log.d(TAG, mSrc + ": " + cursor.getCount() + " rows in " + elapsedMs
                            + " ms for '" + mQuery + "'");
                    cursor.registerContentObserver(new ChangeObserver(cursor));
                    cursor.registerDataSetObserver(new MyDataSetObserver(mSrc, start, cursor));
                    try {
                        Thread.sleep(2000);
                    } catch (InterruptedException ex) {
                        Log.d(TAG, mSrc + ": interrupted");
                    }
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }

        private class ChangeObserver extends ContentObserver {
            private Cursor mCursor;

            public ChangeObserver(Cursor cursor) {
                super(mHandler);
                mCursor = cursor;
            }

            @Override
            public boolean deliverSelfNotifications() {
                return true;
            }

            @Override
            public void onChange(boolean selfChange) {
                mCursor.requery();
            }
        }

        private class MyDataSetObserver extends DataSetObserver {
            private long mStart;
            private Cursor mCursor;
            private int mUpdateCount = 0;

            public MyDataSetObserver(String src, long start, Cursor cursor) {
                mSrc = src;
                mStart = start;
                mCursor = cursor;
            }

            @Override
            public void onChanged() {
                long end = System.nanoTime();
                double elapsedMs = (end - mStart) / 1000000.0d;
                mUpdateCount++;
                Log.d(TAG, mSrc + ", update " + mUpdateCount + ": " + mCursor.getCount()
                        + " rows in " + elapsedMs + " ms");
            }

            @Override
            public void onInvalidated() {
                Log.d(TAG, mSrc + ": invalidated");
            }
        }
    }


}
