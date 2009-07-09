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

import android.content.ComponentName;
import android.database.Cursor;
import android.util.Log;
import android.app.SearchManager;

import java.lang.ref.SoftReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A suggestion session lives from when the user starts typing into the search dialog until
 * he/she is done (either clicked on a result, or dismissed the dialog).  It caches results
 * for the duration of a session, and aggregates stats about the session once it the session is
 * closed.
 *
 * During the session, no {@link SuggestionSource} will be queried more than once for a given query.
 *
 * If a given source returns zero results for a query, that source will be ignored for supersets of
 * that query for the rest of the session.  Sources can opt out by setting their
 * <code>queryAfterZeroResults</code> property to <code>true</code> in searchable.xml
 *
 * If there are no shortcuts or cached entries for a given query, we prefill with the results from
 * the previous query for up to {@link #PREFILL_MILLIS} millis until the first result comes back.
 * This results in a smoother experience with less flickering of zero results.
 *
 * If we think the user is typing, and will continue to type we delay the sending of the queries to
 * the sources so we can cancel them when the next query comes in.
 * See {@link #getRecommendedDelay}.
 *
 * This class is thread safe, guarded by "this", to protect against the fact that {@link #query}
 * and the callbacks via {@link com.android.globalsearch.SuggestionCursor.CursorListener} may be
 * called by different threads (the filter thread of the ACTV, and the main thread respectively).
 * Because {@link #query} is always called from the same thread, this synchronization does not
 * impose any noticeable burden (and it is not necessary to attempt finer grained synchronization
 * within the method).
 */
public class SuggestionSession {
    private static final boolean DBG = false;
    private static final boolean SPEW = false;
    private static final String TAG = "GlobalSearch";

    private final SourceLookup mSourceLookup;
    private final ArrayList<SuggestionSource> mEnabledSources;
    private final ShortcutRepository mShortcutRepo;
    private final Executor mExecutor;
    private final DelayedExecutor mDelayedExecutor;
    private final SuggestionFactory mSuggestionFactory;
    private final SessionCallback mListener;
    private final int mNumPromotedSources;

    // guarded by "this"

    private SessionCache mSessionCache = new SessionCache();

    // the cursor from the last character typed, if any
    private SuggestionCursor mPreviousCursor = null;

    // used to detect the closing of the session
    private final AtomicInteger mOutstandingQueryCount = new AtomicInteger(0);

    private HashSet<ComponentName> mSourceImpressions = new HashSet<ComponentName>();

    // used to quickly lookup whether a source is enabled
    private HashSet<ComponentName> mEnabledByName;

    // holds a ref the pending work that attaches the backer to the cursor so we can cancel it.
    private Cancellable mFireOffRunnable;

    /**
     * The number of sources that have a chance to show results above the "more results" entry
     * in one of {@link #MAX_RESULTS_TO_DISPLAY} slots.
     */
    static final int NUM_PROMOTED_SOURCES = 4;

    /**
     * Maximum number of results to display in the list, not including any
     * built-in suggestions or corpus selection suggestions.
     */
    private static final int MAX_RESULTS_TO_DISPLAY = 7;

    /**
     * Maximum number of results to get from each source.
     */
    private static final int MAX_RESULTS_PER_SOURCE = 51 + MAX_RESULTS_TO_DISPLAY;

    /**
     * How long the promoted source have to respond before the "search the web" and "more results"
     * entries are added to the end of the list, in millis.
     */
    private static final long PROMOTED_SOURCE_DEADLINE = 3500;

    /**
     * How long an individual source has to respond before they will be cancelled.
     */
    static final long SOURCE_TIMEOUT_MILLIS = 10000L;

    static final long PREFILL_MILLIS = 400L;

    // constants for the typing delay heuristic.  see getRecommendedDelay
    static final long TYPING_DELAY_LAST_THREE = 800L;
    static final long TYPING_DELAY_LAST_TWO = 500L;

    /**
     * Interface for receiving notifications from session.
     */
    interface SessionCallback {

        /**
         * Called when the session is over.
         *
         * @param stats The stats of the session.
         */
        void closeSession(SessionStats stats);
    }

    /**
     * @param sourceLookup The sources to query for results
     * @param enabledSources The enabled sources, in the order that they should be queried.  If the
     *        web source is enabled, it will always be first.
     * @param shortcutRepo How to find shortcuts for a given query
     * @param executor Used to execute the asynchronous queriies (passed along to
     *        {@link com.android.globalsearch.QueryMultiplexer}
     * @param delayedExecutor Used to post messages.
     * @param suggestionFactory Used to create particular suggestions.
     * @param listener The listener.
     * @param numPromotedSources The number of sources to query first for the promoted list.
     */
    public SuggestionSession(SourceLookup sourceLookup,
            ArrayList<SuggestionSource> enabledSources,
            ShortcutRepository shortcutRepo,
            Executor executor,
            DelayedExecutor delayedExecutor,
            SuggestionFactory suggestionFactory,
            SessionCallback listener,
            int numPromotedSources) {
        mSourceLookup = sourceLookup;
        mEnabledSources = enabledSources;
        mShortcutRepo = shortcutRepo;
        mExecutor = executor;
        mDelayedExecutor = delayedExecutor;
        mSuggestionFactory = suggestionFactory;
        mListener = listener;
        mNumPromotedSources = numPromotedSources;

        final int numEnabled = enabledSources.size();
        mEnabledByName = new HashSet<ComponentName>(numEnabled);
        for (int i = 0; i < numEnabled; i++) {
            mEnabledByName.add(enabledSources.get(i).getComponentName());
        }
        if (DBG) Log.d(TAG, "starting session");
    }

    /**
     * Queries the current session for a resulting cursor.  The cursor will be backed by shortcut
     * and cached data from this session and then be notified of change as other results come in.
     *
     * @param query The query.
     * @return A cursor.
     */
    public synchronized Cursor query(final String query) {
        mOutstandingQueryCount.incrementAndGet();

        // cancel any pending work
        if (mFireOffRunnable != null) {
            if (mFireOffRunnable.cancel()) {
                // if we succesfully cancelled the runnable, we need to decrement the count here;
                // otherwise it will occur when the cursor is closed.
                mOutstandingQueryCount.decrementAndGet();
            }
            mFireOffRunnable = null;
        }

        final SuggestionCursor cursor = new SuggestionCursor(mDelayedExecutor, query);

        // if the user is still typing, delay the work
        final long recommendedDelay = getRecommendedDelay(getNow());
        if (DBG) Log.d(TAG, "recDelay = " + recommendedDelay);
        if (recommendedDelay == 0) {
            fireStuffOff(cursor, query);
        } else {
            mFireOffRunnable = new Cancellable() {
                public void doRun() {
                    fireStuffOff(cursor, query);
                }
            };
            mDelayedExecutor.postDelayed(mFireOffRunnable, recommendedDelay);
        }

        // if the cursor we are about to return is empty (no cache, no shortcuts),
        // prefill it with the previous results until we hear back from a source
        if (mPreviousCursor != null && cursor.getCount() == 0 && mPreviousCursor.getCount() > 0) {
            cursor.prefill(mPreviousCursor);

            // limit the amount of time we show prefilled results
            mDelayedExecutor.postDelayed(new Runnable() {
                public void run() {
                    cursor.onNewResults();
                }
            }, PREFILL_MILLIS);
        }
        mPreviousCursor = cursor;
        return cursor;
    }


    // the last two key presses
    long mLastLastKey = 0;
    long mLastKey = 0;

    /**
     * The heuristic for deciding how long to delay work in hopese that we might avoid having to do
     * it if we think the user is still typing.
     *
     * @param keyTime The current time.
     * @return The recommended millis to delay work.
     */
    long getRecommendedDelay(long keyTime) {
        final long delta1 = keyTime - mLastKey;
        final long delta2 = mLastKey - mLastLastKey;
        final long avg = (delta2 + delta1) / 2;

        if (DBG) Log.d(TAG, "delta1=" + delta1 + ", delta2=" + delta2 + ", avg=" + avg);

        mLastLastKey = mLastKey;
        mLastKey = keyTime;

        if (avg < TYPING_DELAY_LAST_THREE) return TYPING_DELAY_LAST_THREE;
        if (delta1 < TYPING_DELAY_LAST_TWO) return TYPING_DELAY_LAST_TWO;

        return 0;
    }

    /**
     * Finishes the work necessary to report complete results back to the cursor.  This includes
     * getting the shortcuts, refreshing them, determining which source should be queried, sending
     * off the query to each of them, and setting up the callback from the cursor.
     *
     * @param cursor The cursor the results will be reported to.
     * @param query The query.
     */
    private void fireStuffOff(final SuggestionCursor cursor, final String query) {
        if (DBG) Log.d(TAG, "**************firing of work for '" + query + "'");

        // get shortcuts
        final ArrayList<SuggestionData> shortcuts =
                filterOnlyEnabled(mShortcutRepo.getShortcutsForQuery(query));

        // filter out sources that aren't relevant to this query
        final ArrayList<SuggestionSource> sourcesToQuery =
                filterSourcesForQuery(query, mEnabledSources);

        if (DBG) Log.d(TAG, sourcesToQuery.size() + " sources will be queried.");

        // get the shortcuts to refresh
        final ArrayList<SuggestionData> shortcutsToRefresh = new ArrayList<SuggestionData>();
        for (SuggestionData shortcut : shortcuts) {

            final String shortcutId = shortcut.getShortcutId();
            if (shortcutId == null) continue;

            if (mSessionCache.hasShortcutBeenRefreshed(shortcut.getSource(), shortcutId)) continue;

            shortcutsToRefresh.add(shortcut);
        }

        // make the suggestion backer
        final HashSet<ComponentName> promoted = new HashSet<ComponentName>(sourcesToQuery.size());
        for (int i = 0; i < mNumPromotedSources && i < sourcesToQuery.size(); i++) {
            promoted.add(sourcesToQuery.get(i).getComponentName());
        }
        // cached source results
        final QueryCacheResults queryCacheResults = mSessionCache.getSourceResults(query);

        final SuggestionSource webSearchSource = mSourceLookup.getSelectedWebSearchSource();
        final SourceSuggestionBacker backer = new SourceSuggestionBacker(
                query,
                shortcuts,
                sourcesToQuery,
                promoted,
                webSearchSource,
                queryCacheResults.getResults(),
                mSuggestionFactory.createGoToWebsiteSuggestion(query),
                mSuggestionFactory.createSearchTheWebSuggestion(query),
                MAX_RESULTS_TO_DISPLAY,
                PROMOTED_SOURCE_DEADLINE,
                mSuggestionFactory,
                mSuggestionFactory);

        if (DBG) {
            Log.d(TAG, "starting off with " + queryCacheResults.getResults().size() + " cached "
                    + "sources");
            Log.d(TAG, "identified " + sourcesToQuery.size() + " promoted sources to query");
            Log.d(TAG, "identified " + shortcutsToRefresh.size()
                + " shortcuts out of " + shortcuts.size() + " total shortcuts to refresh");
        }

        // fire off queries / refreshers
        final AsyncMux asyncMux = new AsyncMux(
                mExecutor,
                mDelayedExecutor,
                mSessionCache,
                query,
                shortcutsToRefresh,
                removeCached(sourcesToQuery, queryCacheResults),
                promoted,
                backer,
                mShortcutRepo);

        cursor.attachBacker(asyncMux);
        asyncMux.setListener(cursor);

        cursor.setListener(new SuggestionCursor.CursorListener() {
            private SuggestionData mClicked = null;

            public void onClose(List<SuggestionData> viewedSuggestions) {
                asyncMux.cancel();

                final int numViewed = viewedSuggestions.size();
                if (DBG) {
                    Log.d(TAG, "onClose('" + query + "',  " +
                            numViewed + " displayed");
                }
                for (int i = 0; i < numViewed; i++) {
                    final SuggestionData viewed = viewedSuggestions.get(i);
                    final ComponentName sourceName = viewed.getSource();
                    // only add it if it is from a source we know of (e.g, not a built in one
                    // used for special suggestions like "more results").
                    if (mSourceLookup.getSourceByComponentName(sourceName) != null) {
                        mSourceImpressions.add(sourceName);
                    } else if (SearchManager.INTENT_ACTION_CHANGE_SEARCH_SOURCE.equals(
                            viewed.getIntentAction())) {
                        // a corpus result under "more results"; unpack the component
                        final ComponentName corpusName =
                                ComponentName.unflattenFromString(viewed.getIntentData());
                        if (corpusName != null && asyncMux.hasSourceStarted(corpusName)) {
                            // we only count an impression if the source has at least begun
                            // retrieving its results.
                            mSourceImpressions.add(corpusName);
                        }
                    }
                }

                // when the cursor closes and there aren't any outstanding requests, it means
                // the user has moved on (either clicked on something, dismissed the dialog, or
                // pivoted into app specific search)
                if (mOutstandingQueryCount.decrementAndGet() == 0) {
                    if (DBG) Log.d(TAG, "closing session");
                    mListener.closeSession(new SessionStats(query, mClicked, mSourceImpressions));
                }
            }

            public void onItemClicked(SuggestionData clicked) {
                if (DBG) Log.d(TAG, "onItemClicked");
                mClicked = clicked;
            }

            public void onMoreVisible() {
                if (DBG) Log.d(TAG, "onMoreVisible");
                asyncMux.sendOffAdditionalSourcesQueries();
            }
        });

        asyncMux.sendOffShortcutRefreshers(mSourceLookup);
        asyncMux.sendOffPromotedSourceQueries();

        // refresh the backer after the deadline to force showing of "more results"
        // even if all of the promoted sources haven't responded yet.
        mDelayedExecutor.postDelayed(new Runnable() {
            public void run() {
                cursor.onNewResults();
            }
        }, PROMOTED_SOURCE_DEADLINE);
    }

    /**
     * Filter the list of shortcuts to only include those come from enabled sources.
     *
     * @param shortcutsForQuery The shortcuts.
     * @return A list including only shortcuts from sources that are enabled.
     */
    private ArrayList<SuggestionData> filterOnlyEnabled(
            ArrayList<SuggestionData> shortcutsForQuery) {
        final int numShortcuts = shortcutsForQuery.size();
        if (numShortcuts == 0) return shortcutsForQuery;

        final ArrayList<SuggestionData> result = new ArrayList<SuggestionData>(
                shortcutsForQuery.size());
        for (int i = 0; i < numShortcuts; i++) {
            final SuggestionData shortcut = shortcutsForQuery.get(i);
            if (mEnabledByName.contains(shortcut.getSource())) {
                result.add(shortcut);
            }
        }
        return result;
    }

    /**
     * @param sources The sources
     * @param queryCacheResults The cached results for the current query
     * @return A list of sources not including any of the cached results.
     */
    private ArrayList<SuggestionSource> removeCached(
            ArrayList<SuggestionSource> sources, QueryCacheResults queryCacheResults) {
        final int numSources = sources.size();
        final ArrayList<SuggestionSource> unCached = new ArrayList<SuggestionSource>(numSources);

        for (int i = 0; i < numSources; i++) {
            final SuggestionSource source = sources.get(i);
            if (queryCacheResults.getResult(source.getComponentName()) == null) {
                unCached.add(source);
            }
        }
        return unCached;
    }

    /**
     * Filter the sources to query based on properties of each source related to the query.
     *
     * @param query The query.
     * @param enabledSources The full list of sources.
     * @return A list of sources that should be queried.
     */
    private ArrayList<SuggestionSource> filterSourcesForQuery(
            String query, ArrayList<SuggestionSource> enabledSources) {
        final int queryLength = query.length();
        final int cutoff = Math.max(1, queryLength);
        final ArrayList<SuggestionSource> sourcesToQuery = new ArrayList<SuggestionSource>();

        if (queryLength == 0) return sourcesToQuery;

        if (DBG && SPEW) Log.d(TAG, "filtering enabled sources to those we want to query...");
        for (SuggestionSource enabledSource : enabledSources) {

            // query too short
            if (enabledSource.getQueryThreshold() > cutoff) {
                if (DBG && SPEW) {
                    Log.d(TAG, "skipping " + enabledSource.getLabel() + " (query thresh)");
                }
                continue;
            }

            final ComponentName sourceName = enabledSource.getComponentName();

            // source returned zero results for a prefix of query
            if (!enabledSource.queryAfterZeroResults()
                    && mSessionCache.hasReportedZeroResultsForPrefix(
                    query, sourceName)) {
                if (DBG && SPEW) {
                    Log.d(TAG, "skipping " + enabledSource.getLabel()
                            + " (zero results for prefix)");
                }
                continue;
            }

            if (DBG && SPEW) Log.d(TAG, "adding " + enabledSource.getLabel());
            sourcesToQuery.add(enabledSource);
        }
        return sourcesToQuery;
    }

    long getNow() {
        return System.currentTimeMillis();
    }

    /**
     * Caches results and information to avoid doing unnecessary work within the session.  Helps
     * the session to make the following optimizations:
     * - don't query same source more than once for a given query (subject to memory constraints)
     * - don't validate the same shortcut more than once
     * - don't query a source again if it returned zero results before for a prefix of a given query
     *
     * To avoid hogging memory the list of suggestions returned from sources are referenced from
     * soft references.
     */
    static class SessionCache {

        static final QueryCacheResults EMPTY = new QueryCacheResults();

        private HashMap<String, HashSet<ComponentName>> mZeroResultSources
                = new HashMap<String, HashSet<ComponentName>>();

        private HashMap<String, SoftReference<QueryCacheResults>> mResultsCache
                = new HashMap<String, SoftReference<QueryCacheResults>>();

        private HashSet<String> mRefreshedShortcuts = new HashSet<String>();

        /**
         * @param query The query
         * @param source Identifies the source
         * @return Whether the given source has returned zero results for any prefixes of the
         *   given query.
         */
        synchronized boolean hasReportedZeroResultsForPrefix(
                String query, ComponentName source) {
            final int queryLength = query.length();
            for (int i = 1; i < queryLength; i++) {
                final String subQuery = query.substring(0, queryLength - i);
                final HashSet<ComponentName> zeros = mZeroResultSources.get(subQuery);
                if (zeros != null && zeros.contains(source)) {
                    return true;
                }
            }
            return false;
        }

        /**
         * @param source Identifies the source
         * @param shortcutId The id of the shortcut
         * @return Whether the shortcut id has been validated already
         */
        synchronized boolean hasShortcutBeenRefreshed(
                ComponentName source, String shortcutId) {
            return mRefreshedShortcuts.contains(shortcutId);
        }

        /**
         * @param query The query
         * @return The results for any sources that have reported results.
         */
        synchronized QueryCacheResults getSourceResults(String query) {
            final QueryCacheResults queryCacheResults = getCachedResult(query);
            return queryCacheResults == null ? EMPTY : queryCacheResults;
        }

        /**
         * Reports that a source has provided results for a particular query.
         */
        synchronized void reportSourceResult(String query, SuggestionResult sourceResult) {

            QueryCacheResults queryCacheResults = getCachedResult(query);
            if (queryCacheResults == null) {
                queryCacheResults = new QueryCacheResults();
                mResultsCache.put(query, new SoftReference<QueryCacheResults>(queryCacheResults));
            }
            queryCacheResults.addResult(sourceResult);

            if (!sourceResult.getSource().queryAfterZeroResults()
                    && sourceResult.getSuggestions().isEmpty()) {
                HashSet<ComponentName> zeros = mZeroResultSources.get(query);
                if (zeros == null) {
                    zeros = new HashSet<ComponentName>();
                    mZeroResultSources.put(query, zeros);
                }
                zeros.add(sourceResult.getSource().getComponentName());
            }
        }

        /**
         * Reports that a source has refreshed a shortcut
         */
        synchronized void reportRefreshedShortcut(ComponentName source, String shortcutId) {
            // TODO: take source into account too
            mRefreshedShortcuts.add(shortcutId);
        }

        private QueryCacheResults getCachedResult(String query) {
            final SoftReference<QueryCacheResults> ref = mResultsCache.get(query);
            if (ref == null) return null;

            if (ref.get() == null) {
                if (DBG) Log.d(TAG, "soft ref to results for '" + query + "' GC'd");
            }
            return ref.get();
        }
    }

    /**
     * Holds the results reported back by the sources for a particular query.
     *
     * Preserves order of when they were reported back, provides efficient lookup for a given
     * source
     */
    static class QueryCacheResults {

        private final LinkedHashMap<ComponentName, SuggestionResult> mSourceResults
                = new LinkedHashMap<ComponentName, SuggestionResult>();

        public void addResult(SuggestionResult result) {
            mSourceResults.put(result.getSource().getComponentName(), result);
        }

        public Collection<SuggestionResult> getResults() {
            return mSourceResults.values();
        }

        public SuggestionResult getResult(ComponentName source) {
            return mSourceResults.get(source);
        }
    }

    /**
     * Asynchronously queries sources to get their results for a query and to validate shorcuts.
     *
     * Results are passed through to a wrapped {@link SuggestionBacker} after passing along stats
     * to the session cache.
     */
    static class AsyncMux extends SuggestionBacker {

        private final Executor mExecutor;
        private final DelayedExecutor mDelayedExecutor;
        private final SessionCache mSessionCache;
        private final String mQuery;
        private final ArrayList<SuggestionData> mShortcutsToValidate;
        private final ArrayList<SuggestionSource> mSourcesToQuery;
        private final HashSet<ComponentName> mPromotedSources;
        private final SourceSuggestionBacker mBackerToReportTo;
        private final ShortcutRepository mRepo;

        private QueryMultiplexer mPromotedSourcesQueryMux;
        private QueryMultiplexer mAdditionalSourcesQueryMux;
        private ShortcutRefresher mShortcutRefresher;

        private volatile boolean mCanceled = false;

        /**
         * TODO: just pass in a list of promoted sources and a list of additional sources (or
         * perhaps just the number of promoted sources) once we automatically choose promoted
         * sources based on a ranking returned by the shortcut repo.
         *
         * @param executor required by the query multiplexers.
         * @param delayedExecutor required by the query multiplexers.
         * @param sessionCache results are repoted to the cache as they come in
         * @param query the query the tasks pertain to
         * @param shortcutsToValidate the shortcuts that need to be validated
         * @param sourcesToQuery the sources that need to be queried
         * @param promotedSources those sources that are promoted
         * @param backerToReportTo the backer the results should be passed to
         * @param repo The shortcut repository needed to create the shortcut refresher.
         */
        AsyncMux(
                Executor executor,
                DelayedExecutor delayedExecutor, SessionCache sessionCache,
                String query,
                ArrayList<SuggestionData> shortcutsToValidate,
                ArrayList<SuggestionSource> sourcesToQuery,
                HashSet<ComponentName> promotedSources,
                SourceSuggestionBacker backerToReportTo,
                ShortcutRepository repo) {
            mExecutor = executor;
            mDelayedExecutor = delayedExecutor;
            mSessionCache = sessionCache;
            mQuery = query;
            mShortcutsToValidate = shortcutsToValidate;
            mSourcesToQuery = sourcesToQuery;
            mPromotedSources = promotedSources;
            mBackerToReportTo = backerToReportTo;
            mRepo = repo;
        }


        @Override
        public void snapshotSuggestions(ArrayList<SuggestionData> dest, boolean expandAdditional) {
            mBackerToReportTo.snapshotSuggestions(dest, expandAdditional);
        }

        @Override
        public boolean isResultsPending() {
            return mBackerToReportTo.isResultsPending();
        }

        @Override
        public boolean isShowingMore() {
            return mBackerToReportTo.isShowingMore();
        }

        @Override
        public int getMoreResultPosition() {
            return mBackerToReportTo.getMoreResultPosition();
        }

        @Override
        public boolean reportSourceStarted(ComponentName source) {
            return mBackerToReportTo.reportSourceStarted(source);
        }

        @Override
        public boolean hasSourceStarted(ComponentName source) {
            return mBackerToReportTo.hasSourceStarted(source);
        }

        @Override
        protected boolean addSourceResults(SuggestionResult suggestionResult) {
            if (suggestionResult.getResultCode() == SuggestionResult.RESULT_OK) {
                mSessionCache.reportSourceResult(mQuery, suggestionResult);
            }
            return mBackerToReportTo.addSourceResults(suggestionResult);
        }

        @Override
        protected boolean refreshShortcut(
                ComponentName source, String shortcutId, SuggestionData shortcut) {
            mSessionCache.reportRefreshedShortcut(source, shortcutId);
            return mBackerToReportTo.refreshShortcut(source, shortcutId, shortcut);
        }

        void sendOffShortcutRefreshers(SourceLookup sourceLookup) {
            if (mCanceled) return;
            if (mShortcutRefresher != null) {
                throw new IllegalStateException("Already refreshed once");
            }
            mShortcutRefresher = new ShortcutRefresher(
                    mExecutor, sourceLookup, mShortcutsToValidate,
                    MAX_RESULTS_TO_DISPLAY, this, mRepo);
            if (DBG) Log.d(TAG, "sending shortcut refresher tasks for " +
                    mShortcutsToValidate.size() + " shortcuts.");
            mShortcutRefresher.refresh();
        }

        void sendOffPromotedSourceQueries() {
            if (mCanceled) return;
            if (mPromotedSourcesQueryMux != null) {
                throw new IllegalStateException("Already queried once");
            }

            ArrayList<SuggestionSource> promotedSources =
                    new ArrayList<SuggestionSource>(mPromotedSources.size());

            for (SuggestionSource source : mSourcesToQuery) {
                if (mPromotedSources.contains(source.getComponentName())) {
                    promotedSources.add(source);
                }
            }
            mPromotedSourcesQueryMux = new QueryMultiplexer(
                    mQuery, promotedSources, MAX_RESULTS_PER_SOURCE, MAX_RESULTS_PER_SOURCE,
                    this, mExecutor, mDelayedExecutor);
            if (DBG) Log.d(TAG, "sending '" + mQuery + "' off to " + promotedSources.size() +
                    " promoted sources");
            mBackerToReportTo.reportPromotedQueryStartTime();
            mPromotedSourcesQueryMux.sendQuery();
        }

        void sendOffAdditionalSourcesQueries() {
            if (mCanceled) return;
            if (mAdditionalSourcesQueryMux != null) {
                throw new IllegalStateException("Already queried once");
            }

            final int numAdditional = mSourcesToQuery.size() - mPromotedSources.size();

            if (numAdditional <= 0) {
                return;
            }

            ArrayList<SuggestionSource> additional = new ArrayList<SuggestionSource>(numAdditional);
            for (SuggestionSource source : mSourcesToQuery) {
                if (!mPromotedSources.contains(source.getComponentName())) {
                    additional.add(source);
                }
            }

            mAdditionalSourcesQueryMux = new QueryMultiplexer(
                    mQuery, additional, MAX_RESULTS_TO_DISPLAY, MAX_RESULTS_PER_SOURCE,
                    this, mExecutor, mDelayedExecutor);
            if (DBG) Log.d(TAG, "sending queries off to " + additional.size() + " promoted " +
                    "sources");
            mAdditionalSourcesQueryMux.sendQuery();
        }

        void cancel() {
            mCanceled = true;

            if (mShortcutRefresher != null) {
                mShortcutRefresher.cancel();
            }
            if (mPromotedSourcesQueryMux != null) {
                mPromotedSourcesQueryMux.cancel();
            }
            if (mAdditionalSourcesQueryMux != null) {
                mAdditionalSourcesQueryMux.cancel();
            }
        }
    }

    /**
     * Simple wrapper of a Runnable to make it cancellable.
     */
    private static abstract class Cancellable implements Runnable {

        private boolean mCancelled = false;
        private boolean mHasRun = false;

        /**
         * @return Whether the cancellation was succesful in preventing this action from running.
         */
        public synchronized boolean cancel() {
            mCancelled = true;
            return !mHasRun;
        }

        public synchronized void run() {
            if (mCancelled) return;
            doRun();
            mHasRun = true;
        }

        abstract void doRun();
    }
}
