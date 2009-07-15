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

import android.database.Cursor;
import android.content.Context;
import android.content.ComponentName;
import android.os.Handler;
import android.util.Log;

import java.util.concurrent.Executor;
import java.util.ArrayList;
import java.util.List;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.HashSet;
import java.util.Iterator;

/**
 * Holds onto the current {@link SuggestionSession} and manages its lifecycle.  When a session ends,
 * it gets the session stats and reports them to the {@link ShortcutRepository}.
 */
public class SessionManager implements SuggestionSession.SessionCallback {

    private static final String TAG = "SessionManager";
    private static final boolean DBG = false;
    private static SessionManager sInstance;

    private final Context mContext;

    public static synchronized SessionManager getInstance() {
        return sInstance;
    }

    /**
     * Refreshes the global session manager.
     *
     * @param sources The suggestion sources.
     * @param shortcutRepo The shortcut repository.
     * @param executor The executor passed along to the session.
     * @param handler The handler passed along to the session.
     * @return The up to date session manager.
     */
    public static synchronized SessionManager refreshSessionmanager(Context context,
            SuggestionSources sources, ShortcutRepository shortcutRepo, Executor executor,
            Handler handler) {
        if (DBG) Log.d(TAG, "refreshSessionmanager()");

        sInstance = new SessionManager(context, sources, shortcutRepo, executor, handler);
        return sInstance;
    }

    private SessionManager(Context context,
            SuggestionSources sources, ShortcutRepository shortcutRepo,
            Executor executor, Handler handler) {
        mContext = context;
        mSources = sources;
        mShortcutRepo = shortcutRepo;
        mExecutor = executor;
        mHandler = handler;
    }

    private final SuggestionSources mSources;
    private final ShortcutRepository mShortcutRepo;
    private final Executor mExecutor;
    private final Handler mHandler;
    private SuggestionSession mSession;

    /**
     * Queries the current session for results.
     *
     * @see SuggestionSession#query(String)
     */
    public Cursor query(Context context, String query) {
        if (mSession == null) {
            mSession = createSession();
        }

        return mSession.query(query);
    }

    /** {@inheritDoc} */
    public void closeSession(SessionStats stats) {
        if (DBG) Log.d(TAG, "closeSession");
        mShortcutRepo.reportStats(stats);
        mSession = null;
    }

    private SuggestionSession createSession() {
        if (DBG) Log.d(TAG, "createSession()");
        final SuggestionSource webSearchSource = mSources.getSelectedWebSearchSource();
        
        // Fire off a warm-up query to the web search source, which that source can use for
        // whatever it sees fit. For example, EnhancedGoogleSearchProvider uses this to
        // determine whether a opt-in needs to be shown for use of location.
        if (webSearchSource != null) {
            warmUpWebSource(webSearchSource);
        }

        final ArrayList<SuggestionSource> enabledSources = orderSources(
                mSources.getEnabledSuggestionSources(),
                webSearchSource,
                mShortcutRepo.getSourceRanking(),
                SuggestionSession.NUM_PROMOTED_SOURCES);

        // implement the delayed executor using the handler
        final DelayedExecutor delayedExecutor = new DelayedExecutor() {
            public void postDelayed(Runnable runnable, long delayMillis) {
                mHandler.postDelayed(runnable, delayMillis);
            }

            public void postAtTime(Runnable runnable, long uptimeMillis) {
                mHandler.postAtTime(runnable, uptimeMillis);
            }
        };

        return new SuggestionSession(
                mSources, enabledSources,
                mShortcutRepo, mExecutor,
                delayedExecutor, new SuggestionFactoryImpl(mContext), this,
                SuggestionSession.NUM_PROMOTED_SOURCES, SuggestionSession.CACHE_SUGGESTION_RESULTS);
    }

    private void warmUpWebSource(final SuggestionSource webSearchSource) {
        mExecutor.execute(new Runnable() {
            public void run() {
                try {
                    webSearchSource.getSuggestionTask("", 0, 0).call();
                } catch (Exception e) {
                    Log.e(TAG, "exception from web search warm-up query", e);
                }
            }
        });
    }

    /**
     * Produces a list of sources that are ordered by source ranking.  The ordering is as follows:
     * - the web source is first regardless
     * - the rest of the promoted sources are filled based on the ranking passed in
     * - any unranked sources
     * - the rest of the ranked sources
     *
     * The idea is that unranked sources get a bump until they have enough data to be ranked like
     * the rest, and at the same time, no source can be in the promoted list unless it has a high
     * click through rate for a sustained amount of impressions.
     *
     * @param enabledSources The enabled sources.
     * @param webSearchSource The name of the web search source, or <code>null</code> otherwise.
     * @param sourceRanking The order the sources should be in.
     * @param numPromoted  The number of promoted sources.
     * @return A list of sources that are ordered by the source ranking.
     */
    static ArrayList<SuggestionSource> orderSources(
            List<SuggestionSource> enabledSources,
            SuggestionSource webSearchSource,
            ArrayList<ComponentName> sourceRanking,
            int numPromoted) {

        // get any sources that are in the enabled sources in the order
        final int numSources = enabledSources.size();
        HashMap<ComponentName, SuggestionSource> linkMap =
                new LinkedHashMap<ComponentName, SuggestionSource>(numSources);
        for (int i = 0; i < numSources; i++) {
            final SuggestionSource source = enabledSources.get(i);
            linkMap.put(source.getComponentName(), source);
        }

        // gather set of ranked
        final HashSet<ComponentName> allRanked = new HashSet<ComponentName>(sourceRanking);

        ArrayList<SuggestionSource> ordered = new ArrayList<SuggestionSource>(numSources);

        // start with the web source if it exists
        if (webSearchSource != null) {
            if (DBG) Log.d(TAG, "Adding web search source: " + webSearchSource);
            ordered.add(webSearchSource);
        }

        // add ranked for rest of promoted slots
        final int numRanked = sourceRanking.size();
        int nextRanked = 0;
        for (; nextRanked < numRanked && ordered.size() < numPromoted; nextRanked++) {
            final ComponentName ranked = sourceRanking.get(nextRanked);
            final SuggestionSource source = linkMap.remove(ranked);
            if (DBG) Log.d(TAG, "Adding promoted ranked source: (" + ranked + ") " + source);
            if (source != null) ordered.add(source);
        }

        // now add the unranked
        final Iterator<SuggestionSource> sourceIterator = linkMap.values().iterator();
        while (sourceIterator.hasNext()) {
            SuggestionSource source = sourceIterator.next();
            if (!allRanked.contains(source.getComponentName())) {
                if (DBG) Log.d(TAG, "Adding unranked source: " + source);
                ordered.add(source);
                sourceIterator.remove();
            }
        }

        // finally, any remaining ranked
        for (int i = nextRanked; i < numRanked; i++) {
            final ComponentName ranked = sourceRanking.get(i);
            final SuggestionSource source = linkMap.get(ranked);
            if (DBG) Log.d(TAG, "Adding unpromoted ranked source: (" + ranked + ") " + source);
            if (source != null) ordered.add(source);
        }

        if (DBG) Log.d(TAG, "Ordered sources: " + ordered);
        return ordered;
    }
}
