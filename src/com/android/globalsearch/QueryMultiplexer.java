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

import android.util.Log;

import java.util.concurrent.Executor;
import java.util.concurrent.FutureTask;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.List;
import java.util.ArrayList;

/**
 * Responsible for sending out a query to a list of {@link SuggestionSource}s asynchronously and
 * reporting them back as they arrive to a {@link SuggestionBacker}.
 */
public class QueryMultiplexer implements Runnable {

    // set to true to enable the more verbose debug logging for this file
    private static final boolean DBG = false;
    private static final String TAG = "GlobalSearch";


    private final Executor mExecutor;
    private final List<SuggestionSource> mSources;
    private final SuggestionBacker mReceiver;
    private final String mQuery;
    private final int mMaxResultsPerSource;
    private final int mQueryLimit;

    private ArrayList<SuggestionRequest> mSentRequests;

    /**
     * @param query The query to send to each source.
     * @param sources The sources.
     * @param maxResultsPerSource The maximum number of results each source should respond with,
     *        passsed along to each source as part of the query.
     * @param queryLimit An advisory maximum number that each source should return
     *        in {@link SuggestionResult#getCount()}.
     * @param receiver The receiver of results.
     * @param executor Used to execute each source's {@link SuggestionSource#getSuggestionTask}
     */
    public QueryMultiplexer(String query, List<SuggestionSource> sources, int maxResultsPerSource,
            int queryLimit, SuggestionBacker receiver, Executor executor) {
        mExecutor = executor;
        mQuery = query;
        mSources = sources;
        mReceiver = receiver;
        mMaxResultsPerSource = maxResultsPerSource;
        mQueryLimit = queryLimit;
        mSentRequests = new ArrayList<SuggestionRequest>(mSources.size());
    }

    /**
     * Convenience for usage as {@link Runnable}.
     */
    public void run() {
        sendQuery();
    }

    /**
     * Sends the query to the sources.
     */
    public void sendQuery() {
        for (SuggestionSource source : mSources) {
            final SuggestionRequest suggestionRequest = new SuggestionRequest(source);
            mSentRequests.add(suggestionRequest);
            mExecutor.execute(suggestionRequest);
        }
    }

    /**
     * Cancels the requests that are in progress from sending off the query.
     */
    public void cancel() {
        for (SuggestionRequest sentRequest : mSentRequests) {
            sentRequest.cancel(true);
        }
    }


    /**
     * Once a result of a suggestion task is complete, it will report the suggestions to the mixer.
     */
    private class SuggestionRequest extends FutureTask<SuggestionResult> {

        private final SuggestionSource mSuggestionSource;

        /**
         * @param suggestionSource The suggestion source that this request is for.
         */
        SuggestionRequest(SuggestionSource suggestionSource) {
            super(suggestionSource.getSuggestionTask(mQuery, mMaxResultsPerSource, mQueryLimit));
            mSuggestionSource = suggestionSource;
        }

        @Override
        public void run() {
            mReceiver.onSourceQueryStart(mSuggestionSource.getComponentName());
            super.run();
        }

        /**
         * Cancels the suggestion request.
         *
         * @param mayInterruptIfRunning Whether to interrupt the thread
         * running the suggestion request. Always pass <code>true</code>,
         * to ensure that the request finishes quickly.
         */
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            boolean canceled = super.cancel(mayInterruptIfRunning);
            if (DBG) Log.d(TAG, getTag() + ": Cancelling: " + canceled);
            return canceled;
        }

        // Used in debugging logs.
        private String getTag() {
            return "\"" + mQuery + "\": "
                    + mSuggestionSource.getComponentName().flattenToShortString();
        }

        @Override
        protected void done() {
            try {
                if (isCancelled()) {
                    if (DBG) Log.d(TAG, getTag() + " was cancelled");
                    return;
                }
                final SuggestionResult suggestionResult = get();
                mReceiver.onNewSuggestionResult(suggestionResult);
            } catch (CancellationException e) {
                // The suggestion request was canceled, do nothing.
                // This can happen when the Cursor is closed before
                // the suggestion source returns, but without
                // interrupting any waits.
                if (DBG) Log.d(TAG, getTag() + " threw CancellationException.");
                // Since we were canceled, we don't need to return any results.
            } catch (InterruptedException e) {
                // The suggestion request was interrupted, do nothing.
                // This can happen when the Cursor is closed before
                // the suggestion source returns, by interrupting
                // a wait somewhere.
                if (DBG) Log.d(TAG, getTag() + " threw InterruptedException.");
                // Since we were canceled, we don't need to return any results.
            } catch (ExecutionException e) {
                // The suggestion source threw an exception. We just catch and log it,
                // since we don't want to crash the suggestion provider just
                // because of a buggy suggestion source.
                Log.e(TAG, getTag() + " failed.", e.getCause());
                // return empty results, so that the mixer knows that this source is finished
                SuggestionResult suggestionResult = new SuggestionResult(mSuggestionSource);
                mReceiver.onNewSuggestionResult(suggestionResult);
            }
        }
    }
}
