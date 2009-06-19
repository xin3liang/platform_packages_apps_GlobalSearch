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

/**
 * Holds onto the current {@link SuggestionSession} and manages its lifecycle.  When a session ends,
 * it gets the session stats and reports them to the {@link ShortcutRepository}.
 */
public class SessionManager implements SuggestionSession.SessionCallback {

    private static final String TAG = "SessionManager";
    private static final boolean DBG = false;
    private static SessionManager sInstance;

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
    public static synchronized SessionManager refreshSessionmanager(
            SuggestionSources sources, ShortcutRepository shortcutRepo, Executor executor,
            Handler handler) {
        if (DBG) Log.d(TAG, "refreshSessionmanager()");

        sInstance = new SessionManager(sources, shortcutRepo, executor, handler);
        return sInstance;
    }

    private SessionManager(SuggestionSources sources, ShortcutRepository shortcutRepo,
            Executor executor, Handler handler) {
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
     * @see SuggestionSession#query(android.content.Context, String, boolean)
     */
    public Cursor query(Context context, String query, boolean includeSources) {
        if (mSession == null) {
            mSession = createSession();
        }

        return mSession.query(context, query, includeSources);
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
        final ArrayList<SuggestionSource> enabledSources = orderSources(
                mSources.getEnabledSources(),
                webSearchSource == null ? null : webSearchSource.getComponentName(),
                mShortcutRepo.getSourceRanking());
        return new SuggestionSession(
                mSources, enabledSources,
                mShortcutRepo, mExecutor, mHandler, this);
    }

    /**
     * Produces a list of sources that are ordered by source ranking.  Any sources that do not
     * appear in the source ranking list are appended at the end.  The web search source will always
     * be first.
     *
     * @param sources The sources.
     * @param webSearchSource The name of the web search source, or <code>null</code> otherwise.
      *@param sourceRanking The order the sources should be in.
     * @return A list of sources that are ordered by the source ranking.
     */
    private ArrayList<SuggestionSource> orderSources(
            List<SuggestionSource> sources, ComponentName webSearchSource,
            ArrayList<ComponentName> sourceRanking) {

        // get any sources that are in sourceRanking in the order
        final int numSources = sources.size();
        HashMap<ComponentName, SuggestionSource> linkMap =
                new LinkedHashMap<ComponentName, SuggestionSource>(numSources);
        for (int i = 0; i < numSources; i++) {
            final SuggestionSource source = sources.get(i);
            linkMap.put(source.getComponentName(), source);
        }

        ArrayList<SuggestionSource> ordered = new ArrayList<SuggestionSource>(numSources);

        // start with the web source if it exists
        if (webSearchSource != null) {
            ordered.add(linkMap.remove(webSearchSource));
        }

        final int numRanked = sourceRanking.size();
        for (int i = 0; i < numRanked; i++) {
            final ComponentName name = sourceRanking.get(i);
            final SuggestionSource source = linkMap.remove(name);
            if (source != null) ordered.add(source);
        }

        // add any remaining (in the order they were passed in)
        for (SuggestionSource source : linkMap.values()) {
            ordered.add(source);
        }
        return ordered;
    }
}
