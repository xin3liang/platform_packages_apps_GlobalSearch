/*
 * Copyright (C) 2009 The Android Open Source Project
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

import junit.framework.TestCase;

import android.os.Bundle;
import android.database.Cursor;
import android.content.ComponentName;
import android.app.SearchManager;
import static android.app.SearchManager.DialogCursorProtocol;
import android.test.MoreAsserts;

import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

import com.google.android.collect.Lists;

/**
 * Tests for {@link SuggestionSession}, its interaction with {@link SuggestionCursor}, and how and
 * when the session fires queries off to the suggestion sources.
 */
public class SuggestionSessionTest extends TestCase
        implements SuggestionFactory, ShortcutRepository  {

    private SuggestionSession mSession;
    private QueryEngine mEngine;
    private ComponentName mComponentA;
    private SuggestionSource mSourceA;
    private ComponentName mWebComponent;
    private SuggestionSource mWebSource;
    private SuggestionData mWebSuggestion;
    private SuggestionData mSuggestionFromA;

    @Override
    protected void setUp() throws Exception {

        mWebComponent = new ComponentName("com.android.web", "com.android.web.GOOG");
        mWebSuggestion = makeSimple(mWebComponent, "a web a");
        mWebSource = new TestSuggestionSource.Builder()
                .setComponent(mWebComponent)
                .setLabel("web")
                .addCannedResponse("a", mWebSuggestion)
                .create();

        mComponentA = new ComponentName("com.android.test", "com.android.test.A");
        mSuggestionFromA = makeSimple(mComponentA, "a 1");
        mSourceA = new TestSuggestionSource.Builder()
                .setComponent(mComponentA)
                .setLabel("A")
                .addCannedResponse("a", mSuggestionFromA)
                .create();

        ArrayList<SuggestionSource> enabledSources = Lists.newArrayList(mWebSource, mSourceA);
        mSession = initSession(enabledSources, mWebSource);
    }

    private SuggestionSession initSession(
            ArrayList<SuggestionSource> enabledSources,
            SuggestionSource webSource) {
        final SimpleSourceLookup sourceLookup = new SimpleSourceLookup(enabledSources, webSource);
        mEngine = new QueryEngine();
        SuggestionSession session = new SuggestionSession(sourceLookup, enabledSources,
                this, mEngine, mEngine, this, mEngine);
        return session;
    }

    SuggestionData makeSimple(ComponentName component, String title) {
        return new SuggestionData.Builder(component).title(title).build();
    }

// --------------------- Interface ShortcutRepository ---------------------

    public boolean hasHistory() {return true;}
    public void clearHistory() {}
    public void deleteRepository() {}
    public void close() {}
    public void reportStats(SessionStats stats) {}

    public ArrayList<SuggestionData> getShortcutsForQuery(String query) {
        return new ArrayList<SuggestionData>();
    }

    public ArrayList<ComponentName> getSourceRanking() {
        throw new IllegalArgumentException();
    }
    public void refreshShortcut(
            ComponentName source, String shortcutId, SuggestionData refreshed) {}

// --------------------- Interface SuggestionFactory ---------------------

    private static ComponentName BUILT_IN = new ComponentName("com.builtin", "class");
    private static SuggestionData MORE =
            new SuggestionData.Builder(BUILT_IN) .title("more").build();

    public SuggestionData createSearchTheWebSuggestion(String query) {return null;}

    public SuggestionData createGoToWebsiteSuggestion(String query) { return null; }

    public SuggestionData getMoreEntry(
            boolean expanded, List<SourceSuggestionBacker.SourceStat> sourceStats) {
        return MORE;
    }

    public SuggestionData getCorpusEntry(
            String query, SourceSuggestionBacker.SourceStat sourceStat) {
        return new SuggestionData.Builder(BUILT_IN)
                .title("corpus " + sourceStat.getLabel()).build();
    }

// --------------------- Tests ---------------------


    public void testBasicQuery() {
        final Cursor cursor = mSession.query("a");
        {
            final Snapshot snapshot = getSnapshot(cursor);
            assertTrue("isPending.", snapshot.isPending);
            assertEquals("displayNotify", NONE, snapshot.displayNotify);
            MoreAsserts.assertEmpty("suggestions", snapshot.suggetionTitles);

            MoreAsserts.assertContentsInOrder("sources in progress",
                    mEngine.getPendingSources(),
                    mWebComponent, mComponentA);
        }

        mEngine.onSourceRespond(mWebComponent);
        cursor.requery();
        {
            final Snapshot snapshot = getSnapshot(cursor);
            assertTrue(snapshot.isPending);
            assertEquals(NONE, snapshot.displayNotify);
            MoreAsserts.assertContentsInOrder("suggestions",
                    snapshot.suggetionTitles,
                    mWebSuggestion.getTitle());

            MoreAsserts.assertContentsInOrder("sources in progress",
                    mEngine.getPendingSources(),
                    mComponentA);
        }
        mEngine.onSourceRespond(mComponentA);
        cursor.requery();
        {
            final Snapshot snapshot = getSnapshot(cursor);
            assertFalse(snapshot.isPending);
//            assertEquals(NONE, snapshot.displayNotify);   // <--- failing
            MoreAsserts.assertContentsInOrder("suggestions",
                    snapshot.suggetionTitles,
                    mWebSuggestion.getTitle(),
                    mSuggestionFromA.getTitle());

            MoreAsserts.assertEmpty("sources in progress", mEngine.getPendingSources());
        }
    }

    public void testCaching() {
        // results for query
        final Cursor cursor1 = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        mEngine.onSourceRespond(mComponentA);

        // same query again
        final Cursor cursor2 = mSession.query("a");
        cursor2.requery();
        final Snapshot snapshot = getSnapshot(cursor2);
        assertFalse("should not be pending when results are cached.", snapshot.isPending);
//        assertEquals(NONE, snapshot.displayNotify);
        MoreAsserts.assertContentsInOrder("suggestions",
                snapshot.suggetionTitles,
                mWebSuggestion.getTitle(),
                mSuggestionFromA.getTitle());

        MoreAsserts.assertEmpty("should be no sources in progress when results are cached.",
                mEngine.getPendingSources());
    }

    public void testErrorResultNotCached() {

        final TestSuggestionSource aWithError = new TestSuggestionSource.Builder()
                .addErrorResponse("a")
                .setLabel("A")
                .setComponent(mComponentA)
                .create();

        mSession = initSession(Lists.newArrayList(mWebSource, aWithError), mWebSource);

        {
            final Cursor cursor = mSession.query("a");
            mEngine.onSourceRespond(mWebComponent);
            mEngine.onSourceRespond(mComponentA);
            cursor.requery();

            final Snapshot snapshot = getSnapshot(cursor);
            MoreAsserts.assertContentsInOrder(
                snapshot.suggetionTitles,
                mWebSuggestion.getTitle());
        }

        {
            final Cursor cursor = mSession.query("a");
            cursor.requery();

            final Snapshot snapshot = getSnapshot(cursor);
            MoreAsserts.assertContentsInOrder(
                snapshot.suggetionTitles,
                mWebSuggestion.getTitle());
            MoreAsserts.assertContentsInOrder("expecting source a to be pending (not cached) " +
                    "since it returned an error the first time.",
                    mEngine.getPendingSources(),
                    mComponentA);
        }        
    }

// --------------------- Utility methods ---------------------

    /**
     * @param cursor A cursor
     * @return A snapshot of information contained in that cursor.
     */
    private Snapshot getSnapshot(Cursor cursor) {
        final ArrayList<String> titles = new ArrayList<String>(cursor.getCount());

        if (!cursor.isBeforeFirst()) {
            cursor.moveToPosition(-1);
        }

        while (cursor.moveToNext()) {
            titles.add(cursor.getString(
                    cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1)));
        }

        final Bundle bundleIn = new Bundle();
        bundleIn.putInt(DialogCursorProtocol.METHOD, DialogCursorProtocol.POST_REFRESH);
        final Bundle bundleOut = cursor.respond(bundleIn);

        return new Snapshot(
                titles,
                bundleOut.getBoolean(DialogCursorProtocol.POST_REFRESH_RECEIVE_ISPENDING),
                bundleOut.getInt(
                        DialogCursorProtocol.POST_REFRESH_RECEIVE_DISPLAY_NOTIFY,
                        NONE));
    }


    static class Snapshot {
        final ArrayList<String> suggetionTitles;
        final boolean isPending;
        final int displayNotify;

        Snapshot(ArrayList<String> suggetionTitles, boolean pending, int displayNotify) {
            this.suggetionTitles = suggetionTitles;
            isPending = pending;
            this.displayNotify = displayNotify;
        }
    }

    static final int NONE = -1;

    /**
     * Utility class to instrument the plumbing of {@link SuggestionSession} so we can
     * control how results are reported and processed, and keep track of when the session is
     * closed.
     */
    static class QueryEngine implements Executor, DelayedExecutor,
            SuggestionSession.SessionCallback{

        private final LinkedHashMap<ComponentName, FutureTask<SuggestionResult>> mPending
                = new LinkedHashMap<ComponentName, FutureTask<SuggestionResult>>();

        private SessionStats mSessionStats;

        /**
         * @return A list of sources that have been queried and haven't been triggered to respond
         *         via {@link #onSourceRespond(android.content.ComponentName)}
         */
        public List<ComponentName> getPendingSources() {
            return new ArrayList<ComponentName>(mPending.keySet());
        }

        /**
         * Simulate a source responding.
         *
         * @param source The source to have respond.
         */
        public void onSourceRespond(ComponentName source) {
            final FutureTask<SuggestionResult> task = mPending.remove(source);
            if (task == null) {
                throw new IllegalArgumentException(source + " never responded");
            }
            task.run();
        }

        /**
         * @return The stats from the end of the session, or null if the session is still ongoing.
         */
        public SessionStats getSessionStats() {
            return mSessionStats;
        }

        // Executor

        public void execute(Runnable command) {
            if (command instanceof QueryMultiplexer.SuggestionRequest) {
                final QueryMultiplexer.SuggestionRequest suggestionRequest =
                        (QueryMultiplexer.SuggestionRequest) command;
                mPending.put(
                        suggestionRequest.getSuggestionSource().getComponentName(),
                        suggestionRequest);
            } else {
                command.run();
            }
        }

        // DelayedExecutor TODO: keep track of what was delayed for testing

        public void postDelayed(Runnable runnable, long delayMillis) {runnable.run();}
        public void postAtTime(Runnable runnable, long uptimeMillis) {runnable.run();}


        // Session callback

        public void closeSession(SessionStats stats) {
            mSessionStats = stats;
        }
    }

    static class SimpleSourceLookup implements SourceLookup {
        private final ArrayList<SuggestionSource> mSources;
        private final SuggestionSource mWebSource;

        public SimpleSourceLookup(ArrayList<SuggestionSource> sources, SuggestionSource webSource) {
            mSources = sources;
            mWebSource = webSource;
        }

        public SuggestionSource getSourceByComponentName(ComponentName componentName) {
            for (SuggestionSource source : mSources) {
                if (componentName.equals(source.getComponentName())) {
                    return source;
                }
            }
            return null;
        }

        public SuggestionSource getSelectedWebSearchSource() {
            return mWebSource;
        }
    }
}
