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
import android.util.Log;

import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.CancellationException;
import java.util.ArrayList;

/**
 * Fires off tasks to validate shortcuts, and reports the results back to a
 * {@link SuggestionBacker}.  Also tells {@link com.android.globalsearch.ShortcutRepository} to
 * update the shortcut via {@link ShortcutRepository#refreshShortcut}.
 */
public class ShortcutRefresher {

    static private final String TAG = "GlobalSearch";

    private final Executor mExecutor;
    private final SourceLookup mSourceLookup;
    private final ArrayList<SuggestionData> mShortcuts;
    private final int mMaxToRefresh;
    private final SuggestionBacker mReceiver;

    private final ArrayList<ShortcutRefreshTask> mSent;

    private final ShortcutRepository mRepo;

    /**
     * @param executor Used to execute the tasks.
     * @param sourceLookup Used to lookup suggestion sources by component name.
     * @param shortcuts The shortcuts to refresh.
     * @param maxToRefresh The maximum number of shortcuts to refresh.
     * @param receiver Who to report back to.
     * @param shortcutRepository The repo is also told about shortcut refreshes.
     */
    public ShortcutRefresher(Executor executor, SourceLookup sourceLookup,
            ArrayList<SuggestionData> shortcuts, int maxToRefresh, SuggestionBacker receiver,
            ShortcutRepository shortcutRepository) {
        mExecutor = executor;
        mSourceLookup = sourceLookup;
        mShortcuts = shortcuts;
        mMaxToRefresh = maxToRefresh;
        mReceiver = receiver;
        mRepo = shortcutRepository;

        mSent = new ArrayList<ShortcutRefreshTask>(mMaxToRefresh);
    }

    /**
     * Sends off the refresher tasks.
     */
    public void refresh() {
        final int size = Math.min(mMaxToRefresh, mShortcuts.size());
        for (int i = 0; i < size; i++) {
            final SuggestionData shortcut = mShortcuts.get(i);
            final ComponentName componentName = shortcut.getSource();
            SuggestionSource source = mSourceLookup.getSourceByComponentName(componentName);
            
            // If we can't find the source then invalidate the shortcut. Otherwise, send off
            // the refresh task.
            if (source == null) {
                mExecutor.execute(new Runnable() {
                    public void run() {
                        mRepo.refreshShortcut(componentName, shortcut.getShortcutId(), null);
                        mReceiver.onRefreshShortcut(componentName, shortcut.getShortcutId(), null);
                    }
                });
            } else {
                final ShortcutRefreshTask refreshTask = new ShortcutRefreshTask(
                        source, shortcut.getShortcutId(), mReceiver, mRepo);
                mSent.add(refreshTask);
                mExecutor.execute(refreshTask);
            }
        }
    }

    /**
     * Cancels the tasks.
     */
    public void cancel() {
        for (ShortcutRefreshTask shortcutRefreshTask : mSent) {
            shortcutRefreshTask.cancel(true);
        }
    }

    /**
     * Validates a shortcut with a source and reports the result to a {@link SuggestionBacker}.
     */
    private static class ShortcutRefreshTask extends FutureTask<SuggestionData> {

        private final SuggestionSource mSource;
        private final String mShortcutId;
        private final SuggestionBacker mReceiver;
        private final ShortcutRepository mRepo;

        /**
         * @param source The source that should validate the shortcut.
         * @param shortcutId The id of the shortcut.
         * @param receiver Who to report back to when the result is in.
         * @param repo
         */
        ShortcutRefreshTask(SuggestionSource source, String shortcutId,
                SuggestionBacker receiver, ShortcutRepository repo) {
            super(source.getShortcutValidationTask(shortcutId));
            mSource = source;
            mShortcutId = shortcutId;
            mReceiver = receiver;
            mRepo = repo;
        }

        @Override
        protected void done() {
            if (isCancelled()) return;

            try {
                final SuggestionData refreshed = get();
                mRepo.refreshShortcut(mSource.getComponentName(), mShortcutId, refreshed);
                mReceiver.onRefreshShortcut(mSource.getComponentName(), mShortcutId, refreshed);
            } catch (CancellationException e) {
              // validation task was cancelled, nothing left to do
            } catch (InterruptedException e) {
                // ignore
            } catch (ExecutionException e) {
                Log.e(TAG, "failed to refresh shortcut from "
                        + mSource.getComponentName().flattenToString()
                        + " for shorcut id " + mShortcutId,
                        e);
            }
        }
    }
}
