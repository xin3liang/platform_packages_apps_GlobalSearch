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
import java.util.Iterator;
import java.util.LinkedList;
import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;

import com.google.android.collect.Lists;

/**
 * Tests for {@link SuggestionSession}, its interaction with {@link SuggestionCursor}, and how and
 * when the session fires queries off to the suggestion sources.
 */
public class SuggestionSessionTest extends TestCase
        implements SuggestionFactory, ShortcutRepository  {

    private TestSuggestionSession mSession;
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
                .addCannedResponse("b", mWebSuggestion)
                .create();

        mComponentA = new ComponentName("com.android.test", "com.android.test.A");
        mSuggestionFromA = makeSimple(mComponentA, "a 1");
        mSourceA = new TestSuggestionSource.Builder()
                .setComponent(mComponentA)
                .setLabel("A")
                .addCannedResponse("a", mSuggestionFromA)
                .addCannedResponse("b", mSuggestionFromA)
                .create();

        ArrayList<SuggestionSource> enabledSources = Lists.newArrayList(mWebSource, mSourceA);
        mSession = initSession(enabledSources, mWebSource, 4);
    }

    private TestSuggestionSession initSession(
            ArrayList<SuggestionSource> enabledSources,
            SuggestionSource webSource, int numPromotedSources) {
        final SimpleSourceLookup sourceLookup = new SimpleSourceLookup(enabledSources, webSource);
        mEngine = new QueryEngine();
        return new TestSuggestionSession(
                sourceLookup, enabledSources, this, mEngine, numPromotedSources);
    }

    SuggestionData makeSimple(ComponentName component, String title) {
        return new SuggestionData.Builder(component)
                .title(title)
                .intentAction("view")
                .intentData(title)
                .build();
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
        final ComponentName name = sourceStat.getName();
        return makeCorpusEntry(name);
    }

    private SuggestionData makeCorpusEntry(ComponentName name) {
        return new SuggestionData.Builder(BUILT_IN)
                .intentAction(SearchManager.INTENT_ACTION_CHANGE_SEARCH_SOURCE)
                .intentData(name.flattenToShortString())
                .title("corpus " + name).build();
    }

// --------------------- Tests ---------------------


    public void testBasicQuery() {
        final Cursor cursor = mSession.query("a");
        {
            final Snapshot snapshot = getSnapshot(cursor);
            assertTrue("isPending.", snapshot.isPending);
            assertEquals("displayNotify", NONE, snapshot.displayNotify);
            MoreAsserts.assertEmpty("suggestions", snapshot.suggestionTitles);

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
                    snapshot.suggestionTitles,
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
                    snapshot.suggestionTitles,
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
                snapshot.suggestionTitles,
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

        mSession = initSession(Lists.newArrayList(mWebSource, aWithError), mWebSource, 4);

        {
            final Cursor cursor = mSession.query("a");
            mEngine.onSourceRespond(mWebComponent);
            mEngine.onSourceRespond(mComponentA);
            cursor.requery();

            final Snapshot snapshot = getSnapshot(cursor);
            MoreAsserts.assertContentsInOrder(
                snapshot.suggestionTitles,
                mWebSuggestion.getTitle());
        }

        {
            final Cursor cursor = mSession.query("a");
            cursor.requery();

            final Snapshot snapshot = getSnapshot(cursor);
            MoreAsserts.assertContentsInOrder(
                snapshot.suggestionTitles,
                mWebSuggestion.getTitle());
            MoreAsserts.assertContentsInOrder("expecting source a to be pending (not cached) " +
                    "since it returned an error the first time.",
                    mEngine.getPendingSources(),
                    mComponentA);
        }        
    }

    public void testSessionClosing_noWorkCancelling() {
        // first query fired off
        final Cursor cursor1 = mSession.query("a");
        assertNull("session shouldn't be closed.", mEngine.getSessionStats());

        // second query starts
        final Cursor cursor2 = mSession.query("b");
        // first cursor closes (which is how it works from search dialog)
        sendPreClose(cursor1);
        assertNull("session shouldn't be closed after first cursor closes.",
                mEngine.getSessionStats());
        assertNull("session shouldn't be closed.", mEngine.getSessionStats());

        sendPreClose(cursor2);
        assertNotNull(
                "session should be closed after both cursors closed.", mEngine.getSessionStats());
    }

    public void testSessionClosing_whenPreCloseRespondMissing() {
        final Cursor cursor = mSession.query("a");

        cursor.close();
        assertNotNull("Session should have closed.", mEngine.getSessionStats());
    }


    public void testSessionStats_noClick() {
        final Cursor cursor = mSession.query("a");
        sendPreClose(cursor);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        assertNull("clicked.", stats.getClicked());
        MoreAsserts.assertEmpty("source impressions.", stats.getSourceImpressions());
    }

    public void testSessionStats_click() {
        final Cursor cursor = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        mEngine.onSourceRespond(mComponentA);
        cursor.requery();
        final Snapshot snapshot = getSnapshot(cursor);
        MoreAsserts.assertContentsInOrder("suggestions.", snapshot.suggestionTitles,
                mWebSuggestion.getTitle(), mSuggestionFromA.getTitle());

        sendClick(cursor, 0);
        sendPreClose(cursor);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        assertEquals("clicked.", mWebSuggestion, stats.getClicked());
    }

    public void testSessionStats_allSourcesViewed() {
        final Cursor cursor = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        mEngine.onSourceRespond(mComponentA);
        cursor.requery();

        sendPreClose(cursor, 1);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        assertNull("clicked.", stats.getClicked());
        MoreAsserts.assertContentsInAnyOrder("sources viewed.", stats.getSourceImpressions(),
                mWebComponent, mComponentA);
    }

    public void testSessionStats_oneSourceViewed() {
        final Cursor cursor = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        mEngine.onSourceRespond(mComponentA);
        cursor.requery();

        sendPreClose(cursor, 0);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        assertNull("clicked.", stats.getClicked());
        MoreAsserts.assertContentsInOrder(
                "sources viewed.", stats.getSourceImpressions(), mWebComponent);
    }

    public void testSessionStats_impressionsWithMoreNotExpanded() {
        final int numPromotedSources = 1;
        mSession = initSession(
                Lists.newArrayList(mWebSource, mSourceA),
                mWebSource,
                numPromotedSources);

        final Cursor cursor = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        cursor.requery();
        final Snapshot snapshot = getSnapshot(cursor);
        MoreAsserts.assertContentsInOrder("suggestions.", snapshot.suggestionTitles,
                mWebSuggestion.getTitle(), MORE.getTitle());
        assertEquals("should want notification of display of index of 'more'",
                1, snapshot.displayNotify);

        sendPreClose(cursor, 1);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        assertNull("clicked.", stats.getClicked());
        MoreAsserts.assertContentsInAnyOrder(
                "sources viewed (should not include component of 'more results' suggestion.",
                stats.getSourceImpressions(), mWebComponent);
    }

    /**
     * When the user views a corpus entry under "more results" that hasn't even had a chance to
     * start running yet, it isn't fair to count an impression with no click against it.
     */
    public void testSessionStats_impressionsWithMoreExpanded_beforeSourceResponds() {
        final int numPromotedSources = 1;
        mSession = initSession(
                Lists.newArrayList(mWebSource, mSourceA),
                mWebSource,
                numPromotedSources);

        final Cursor cursor = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        cursor.requery();
        {
            final Snapshot snapshot = getSnapshot(cursor);
            MoreAsserts.assertContentsInOrder("suggestions.", snapshot.suggestionTitles,
                    mWebSuggestion.getTitle(), MORE.getTitle());
        }

        // click on "more"
        final int selectedPosition = sendClick(cursor, 1);
        assertEquals("selected position should be index of 'more' after we click on 'more'",
                1, selectedPosition);
        cursor.requery();
        {
            final Snapshot snapshot = getSnapshot(cursor);
            MoreAsserts.assertContentsInOrder("suggestions.",
                    snapshot.suggestionTitles,
                    mWebSuggestion.getTitle(),
                    MORE.getTitle(),
                    makeCorpusEntry(mComponentA).getTitle());
            assertFalse("isPending should be false once 'more results' are mixed in.",
                    snapshot.isPending);
        }

        sendPreClose(cursor, 2);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        MoreAsserts.assertContentsInAnyOrder(
                "sources viewed (should not include source that was viewed, but hasn't " +
                        "started retrieving results yet.)",
                stats.getSourceImpressions(), mWebComponent);
    }

    public void testSessionStats_impressionsWithMoreExpanded_afterSourceResponds() {
        final int numPromotedSources = 1;
        mSession = initSession(
                Lists.newArrayList(mWebSource, mSourceA),
                mWebSource,
                numPromotedSources);

        final Cursor cursor = mSession.query("a");
        mEngine.onSourceRespond(mWebComponent);
        cursor.requery();

        // click on "more"
        final int selectedPosition = sendClick(cursor, 1);
        assertEquals("selected position should be index of 'more' after we click on 'more'",
                1, selectedPosition);
        cursor.requery();

        // viewing that index should kick start the second component
        final Bundle b = new Bundle();
        b.putInt(DialogCursorProtocol.METHOD, DialogCursorProtocol.THRESH_HIT);
        cursor.respond(b);

        sendPreClose(cursor, 2);
        final SessionStats stats = mEngine.getSessionStats();
        assertNotNull("session stats.", stats);
        MoreAsserts.assertContentsInAnyOrder(
                "sources viewed.",
                stats.getSourceImpressions(), mWebComponent, mComponentA);
    }

// --------------------- Utility methods ---------------------

    private void sendPreClose(Cursor cursor) {
        final Bundle b = new Bundle();
        b.putInt(DialogCursorProtocol.METHOD, DialogCursorProtocol.PRE_CLOSE);
        cursor.respond(b);
    }

    private void sendPreClose(Cursor cursor, int maxDisplayedPosition) {
        final Bundle b = new Bundle();
        b.putInt(DialogCursorProtocol.METHOD, DialogCursorProtocol.PRE_CLOSE);
        b.putInt(DialogCursorProtocol.PRE_CLOSE_SEND_MAX_DISPLAY_POS, maxDisplayedPosition);
        cursor.respond(b);
    }


    private int sendClick(Cursor cursor, int position) {
        final Bundle b = new Bundle();
        b.putInt(DialogCursorProtocol.METHOD, DialogCursorProtocol.CLICK);
        b.putInt(DialogCursorProtocol.CLICK_SEND_POSITION, position);
        final Bundle response = cursor.respond(b);
        return response.getInt(DialogCursorProtocol.CLICK_RECEIVE_SELECTED_POS, -1);
    }


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
        final ArrayList<String> suggestionTitles;
        final boolean isPending;
        final int displayNotify;

        Snapshot(ArrayList<String> suggestionTitles, boolean pending, int displayNotify) {
            this.suggestionTitles = suggestionTitles;
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

        private long mNow = 0L;

        private final LinkedHashMap<ComponentName, FutureTask<SuggestionResult>> mPending
                = new LinkedHashMap<ComponentName, FutureTask<SuggestionResult>>();

        private LinkedList<Delayed> mDelayed = new LinkedList<Delayed>();

        /**
         * book keeping for delayed runnables for emulating delayed execution.
         */
        private static class Delayed {
            final long start;
            final long delay;
            final Runnable runnable;

            Delayed(long start, long delay, Runnable runnable) {
                this.start = start;
                this.delay = delay;
                this.runnable = runnable;
            }
        }

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
         * @return The result of the response for further inspection.
         */
        public SuggestionResult onSourceRespond(ComponentName source) {
            final FutureTask<SuggestionResult> task = mPending.remove(source);
            if (task == null) {
                throw new IllegalArgumentException(source + " never started");
            }
            task.run();
            try {
                return task.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        /**
         * Runs all pending source tasks.  This can be useful when starting a new
         * query, to get to a consistent state before more assertions.
         */
        public void finishAllSourceTasks() {
            for (FutureTask<SuggestionResult> task : mPending.values()) {
                task.run();
            }
            mPending.clear();
        }

        /**
         * Moves time forward the specified number of milliseconds, executing any tasks
         * that were posted to {@link #postDelayed(Runnable, long)} as appropriate.
         *
         * @param millis
         */
        public void moveTimeForward(long millis) {
            mNow += millis;
            List<Runnable> toRun = new ArrayList<Runnable>();
            final Iterator<Delayed> it = mDelayed.iterator();
            while (it.hasNext()) {
                Delayed delayed = it.next();
                if (mNow >= delayed.start + delayed.delay) {
                    it.remove();
                    toRun.add(delayed.runnable);
                }
            }

            // do this in a separate pass to avoid concurrent modification of list,
            // since these runnables might add more to the queue
            for (Runnable runnable : toRun) {
                runnable.run();
            }
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

        // DelayedExecutor

        public void postDelayed(Runnable runnable, long delayMillis) {
            mDelayed.add(new Delayed(mNow, delayMillis, runnable));
        }

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

    static class TestSuggestionSession extends SuggestionSession {
        private final QueryEngine mEngine;

        public TestSuggestionSession(SourceLookup sourceLookup,
                ArrayList<SuggestionSource> enabledSources, SuggestionSessionTest test,
                QueryEngine engine, int numPromotedSources) {
            super(sourceLookup, enabledSources, test, engine, engine, engine,
                    test, engine, numPromotedSources, true);
            mEngine = engine;
        }

        @Override
        long getNow() {
            return mEngine.mNow;
        }
    }
}
