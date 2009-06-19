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
import android.database.Cursor;
import android.os.Handler;
import android.util.Log;

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
 * the previous query for up to {@link #DONE_TYPING_REST} millis until the first result comes back.
 * This results in a smoother experience with less flickering of zero results.
 *
 * If we think the user is typing, and will continue to type (they haven't stopped typing for at
 * least {@link #DONE_TYPING_REST} millis), we delay the sending of the queries to the sources so
 * we can cancel them when the next query comes in.
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

    private final SuggestionSources mSources;
    private final ArrayList<SuggestionSource> mEnabledSources;
    private final ShortcutRepository mShortcutRepo;
    private final Executor mExecutor;
    private final Handler mHandler;
    private final SessionCallback mListener;

    // guarded by "this"

    private SessionCache mSessionCache = new SessionCache();

    // the last time the user typed a character during this session.
    private long mLastCharTime = 0;

    // the cursor from the last character typed, if any
    private SuggestionCursor mPreviousCursor = null;

    // used to detect the closing of the session
    private final AtomicInteger mOutstandingQueryCount = new AtomicInteger(0);

    private HashSet<ComponentName> mSourceImpressions = new HashSet<ComponentName>();

    private static final int NUM_PROMOTED_SOURCES = 4;

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
     * The number of millis the user must stop typing before we consider them to be 'at rest', that
     * is, we think they are done typing.  See top class level javadoc for more details.
     */
    private static final long DONE_TYPING_REST = 500L;

    /**
     * How long the promoted source have to respond before the "search the web" and "more results"
     * entries are added to the end of the list, in millis.
     */
    private static final long PROMOTED_SOURCE_DEADLINE = 3500;

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
     * @param sources The sources to query for results
     * @param enabledSources The enabled sources, in the order that they should be queried.
     * @param shortcutRepo How to find shortcuts for a given query
     * @param executor Used to execute the asynchronous queriies (passed along to
     *        {@link QueryMultiplexer}
     * @param handler Used to post messages.
     * @param listener The listener.
     */
    public SuggestionSession(SuggestionSources sources,
            ArrayList<SuggestionSource> enabledSources,
            ShortcutRepository shortcutRepo,
            Executor executor,
            Handler handler,
            SessionCallback listener) {
        mSources = sources;
        mEnabledSources = enabledSources;
        mShortcutRepo = shortcutRepo;
        mExecutor = executor;
        mHandler = handler;
        mListener = listener;
    }

    /**
     * Queries the current session for a resulting cursor.  The cursor will be backed by shortcut
     * and cached data from this session and then be notified of change as other results come in.
     *
     * @param context Used to load resources.
     * @param query The query.
     * @param includeSources Whether to include corpus selection suggestions.
     * @return A cursor.
     */
    public synchronized Cursor query(Context context, final String query, boolean includeSources) {
        mOutstandingQueryCount.incrementAndGet();

        // update typing speed
        long now = getNow();
        long sinceLast = now - mLastCharTime;
        mLastCharTime = now;
        if (DBG) Log.d(TAG, "sinceLast=" + sinceLast);

        // get shortcuts
        final ArrayList<SuggestionData> shortcuts = mShortcutRepo.getShortcutsForQuery(query);

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
        final SuggestionFactory resultFactory = new SuggestionFactory(context, query);
        final HashSet<ComponentName> promoted = new HashSet<ComponentName>(sourcesToQuery.size());
        for (int i = 0; i < NUM_PROMOTED_SOURCES && i < sourcesToQuery.size(); i++) {
            promoted.add(sourcesToQuery.get(i).getComponentName());
        }
        // cached source results
        final QueryCacheResults queryCacheResults = mSessionCache.getSourceResults(query);

        final SuggestionSource webSearchSource = mSources.getSelectedWebSearchSource();
        final SourceSuggestionBacker backer = new SourceSuggestionBacker(
                shortcuts,
                sourcesToQuery,
                promoted,
                webSearchSource,
                queryCacheResults.getResults(),
                resultFactory.createGoToWebsiteSuggestion(),
                resultFactory.createSearchTheWebSuggestion(),
                MAX_RESULTS_TO_DISPLAY,
                PROMOTED_SOURCE_DEADLINE,
                resultFactory,
                resultFactory);

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
                mSessionCache,
                query,
                shortcutsToRefresh,
                removeCached(sourcesToQuery, queryCacheResults),
                promoted,
                backer,
                mShortcutRepo);

        // create cursor
        final SuggestionCursor cursor =
                new SuggestionCursor(asyncMux, mHandler, query, includeSources);
        asyncMux.setListener(cursor);

        cursor.setListener(new SuggestionCursor.CursorListener() {
            private SuggestionData mClicked = null;

            public void onClose(List<SuggestionData> viewedSuggestions) {
                asyncMux.cancel();

                if (DBG) {
                    Log.d(TAG, "onClose('" + query + "',  " +
                            viewedSuggestions.size() + " displayed");
                }
                for (SuggestionData viewedSuggestion : viewedSuggestions) {
                    mSourceImpressions.add(viewedSuggestion.getSource());
                }

                // when the cursor closes and there aren't any outstanding requests, it means
                // the user has moved on (either clicked on something, dismissed the dialog, or
                // pivoted into app specific search)
                if (mOutstandingQueryCount.decrementAndGet() == 0) {
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

        // Send off the promoted source queries and shorcut refresh tasks.
        // if the user has slowed down typing, send off the tasks immediately, otherwise,
        // schedule them to go after a delay.
        final boolean doneTyping = sinceLast >= DONE_TYPING_REST;
        if (doneTyping) {
            sendOffPromotedSourceAndShortcutTasks(asyncMux, cursor);
        } else {
            // we also use DONE_TYPING_REST as the delay itself so that if we receive another
            // character within this window, we will cancel the requests before they get sent
            // (in the cursor close callback)
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    sendOffPromotedSourceAndShortcutTasks(asyncMux, cursor);
                 }
            }, DONE_TYPING_REST);
        }

        // if the cursor we are about to return is empty (no cache, no shortcuts),
        // prefill it with the previous results until we hear back from a source
        if (mPreviousCursor != null && cursor.getCount() == 0 && mPreviousCursor.getCount() > 0) {
            cursor.prefill(mPreviousCursor);

            // limit the amount of time we show prefilled results
            mHandler.postDelayed(new Runnable() {
                public void run() {
                    cursor.onNewResults();
                }
            }, DONE_TYPING_REST);
        }
        mPreviousCursor = cursor;
        return cursor;
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
    private ArrayList<SuggestionSource> filterSourcesForQuery(String query, ArrayList<SuggestionSource> enabledSources) {
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

    /**
     * Sends the tasks to query the promoted sources, and to refresh the shortcuts being shown.
     *
     * @param asyncMux Used to send off the tasks.
     * @param cursor Refreshed after the promoted source deadeline.
     */
    private void sendOffPromotedSourceAndShortcutTasks(
            final AsyncMux asyncMux, final SuggestionCursor cursor) {
        asyncMux.sendOffShortcutRefreshers(mSources);
        asyncMux.sendOffPromotedSourceQueries();

        // refresh the backer after the deadline
        mHandler.postDelayed(new Runnable() {
            public void run() {
                cursor.onNewResults();
            }
        }, PROMOTED_SOURCE_DEADLINE);
    }

    static private long getNow() {
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
         * @param executor used to create the shortcut refresher and request muliplexors
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
                SessionCache sessionCache,
                String query,
                ArrayList<SuggestionData> shortcutsToValidate,
                ArrayList<SuggestionSource> sourcesToQuery,
                HashSet<ComponentName> promotedSources,
                SourceSuggestionBacker backerToReportTo,
                ShortcutRepository repo) {
            mExecutor = executor;
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
        protected boolean addSourceResults(SuggestionResult suggestionResult) {
            mSessionCache.reportSourceResult(mQuery, suggestionResult);
            return mBackerToReportTo.addSourceResults(suggestionResult);
        }

        @Override
        protected boolean refreshShortcut(
                ComponentName source, String shortcutId, SuggestionData shortcut) {
            mSessionCache.reportRefreshedShortcut(source, shortcutId);
            return mBackerToReportTo.refreshShortcut(source, shortcutId, shortcut);
        }

        void sendOffShortcutRefreshers(SuggestionSources sources) {
            if (mCanceled) return;
            if (mShortcutRefresher != null) {
                throw new IllegalStateException("Already refreshed once");
            }
            mShortcutRefresher = new ShortcutRefresher(
                    mExecutor, sources, mShortcutsToValidate, MAX_RESULTS_TO_DISPLAY, this, mRepo);
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
                    this, mExecutor);
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
                Log.w(TAG, "sendOffSendOffAdditionalSourcesQueries called when there are no " +
                        "non-promoted sources to query.");
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
                    this, mExecutor);
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
}
