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
import android.util.Log;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.HashMap;

/**
 * Source suggestion backer shows (that is, snapshots) the results in the following order:
 * - shortcuts
 * - results from promoted sources
 * - a "search the web for 'query'" entry
 * - a "more" item that, when expanded is followed by
 * - an entry for each promoted source that has more results than was displayed above
 * - an entry for each non-promoted source
 *
 * The "search the web" and "more" entries appear only after the promoted sources are given
 * a chance to return their results (either they all return their results, or the timeout elapses).
 *
 * Some set of sources are deemed 'promoted' at the begining via {@link #mPromotedSources}.  These
 * are the sources that will get their results shown at the top.  However, if a promoted source
 * fails to report within {@link #mPromotedSourceDeadline}, they will be removed from the promoted
 * list, and only shown in the "more results" section.
 */
public class SourceSuggestionBacker extends SuggestionBacker {

    private static final boolean DBG = false;
    private static final String TAG = "GlobalSearch";
    private int mIndexOfMore;
    private boolean mShowingMore;

    interface MoreExpanderFactory {

        /**
         * @param expanded Whether the entry should appear expanded.
         * @param sourceStats The entries that will appear under "more results".
         * @return An entry that will hold the "more results" toggle / expander.
         */
        SuggestionData getMoreEntry(boolean expanded, List<SourceStat> sourceStats);
    }

    interface CorpusResultFactory {

        /**
         * Creates a result to be shown representing the results available for a corpus.
         *
         * @param sourceStat Information about the source.
         * @return A result displaying this information.
         */
        SuggestionData getCorpusEntry(SourceStat sourceStat);
    }

    private final List<SuggestionData> mShortcuts;
    private final List<SuggestionSource> mSources;
    private final HashSet<ComponentName> mPromotedSources;
    private final SuggestionSource mSelectedWebSearchSource;
    private final SuggestionData mGoToWebsiteSuggestion;
    private final SuggestionData mSearchTheWebSuggestion;
    private final MoreExpanderFactory mMoreFactory;
    private final CorpusResultFactory mCorpusFactory;
    private final int mMaxPromotedSlots;
    private final long mPromotedSourceDeadline;
    private long mPromotedQueryStartTime;
    
    // The suggestion to pin to the bottom of the list, if any, coming from the web search source.
    // This is used by the Google search provider to pin a "Manage search history" item to the
    // bottom whenever we show search history related suggestions.
    private SuggestionData mPinToBottomSuggestion;

    private final LinkedHashMap<ComponentName, SuggestionResult> mReportedResults =
            new LinkedHashMap<ComponentName, SuggestionResult>();
    private final HashSet<ComponentName> mReportedBeforeDeadline
            = new HashSet<ComponentName>();

    private final HashSet<String> mShortcutIntentKeys = new HashSet<String>();

    /**
     * @param shortcuts To be shown at top.
     * @param sources The sources expected to report
     * @param promotedSources The promoted sources expecting to report
     * @param selectedWebSearchSource the currently selected web search source
     * @param goToWebsiteSuggestion The "go to website" entry to show if appropriate
     * @param searchTheWebSuggestion The "search the web" entry to show if appropriate
     * @param maxPromotedSlots The maximum numer of results to show for the promoted sources
     * @param promotedSourceDeadline How long to wait for the promoted sources before mixing in the
     *   results and displaying the "search the web" and "more results" entries.
     * @param moreFactory How to create the expander entry
     * @param corpusFactory How to create results for each corpus
     */
    public SourceSuggestionBacker(
            List<SuggestionData> shortcuts,
            List<SuggestionSource> sources,
            HashSet<ComponentName> promotedSources,
            SuggestionSource selectedWebSearchSource,
            SuggestionData goToWebsiteSuggestion,
            SuggestionData searchTheWebSuggestion,
            int maxPromotedSlots,
            long promotedSourceDeadline,
            MoreExpanderFactory moreFactory,
            CorpusResultFactory corpusFactory) {

        if (promotedSources.size() > maxPromotedSlots) {
            throw new IllegalArgumentException("more promoted sources than there are slots " +
                    "provided");
        }

        mShortcuts = shortcuts;
        mGoToWebsiteSuggestion = goToWebsiteSuggestion;
        mSearchTheWebSuggestion = searchTheWebSuggestion;
        mMoreFactory = moreFactory;
        mPromotedSourceDeadline = promotedSourceDeadline;
        mCorpusFactory = corpusFactory;
        mSources = sources;
        mPromotedSources = promotedSources;
        mMaxPromotedSlots = maxPromotedSlots;
        mSelectedWebSearchSource = selectedWebSearchSource;

        mPromotedQueryStartTime = getNow();

        for (SuggestionData shortcut : shortcuts) {
            mShortcutIntentKeys.add(makeSuggestionKey(shortcut));
        }
    }

    /**
     * Sets the time that the promoted sources were queried, if different from the creation
     * time.  This is necessary when the backer is created, but the sources are queried after
     * a delay.
     */
    public synchronized void reportPromotedQueryStartTime() {
        mPromotedQueryStartTime = getNow();
    }

    /**
     * Adds a cached result; one that should be displayed as if it is a source that reported even
     * though we are not expecting it to report via {@link #addSourceResults(SuggestionResult)}.
     *
     * @param result The result.
     * @param promoted Whether it is a promoted source.
     */
    public synchronized void addCachedSourceResult(SuggestionResult result, boolean promoted) {
        mSources.add(result.getSource());
        if (promoted) mPromotedSources.add(result.getSource().getComponentName());

        addSourceResults(result);
    }

    /**
     * @return Whether the deadline has passed for promoted sources to report before mixing in
     *   the rest of the results and displaying the "search the web" and "more results" entries.
     */
    private boolean isPastDeadline() {
        return getNow() - mPromotedQueryStartTime >= mPromotedSourceDeadline;
    }

    @Override
    public synchronized void snapshotSuggestions(
            ArrayList<SuggestionData> dest, boolean expandAdditional) {
        if (DBG) Log.d(TAG, "snapShotSuggestions");
        mIndexOfMore = snapshotSuggestionsInternal(dest, expandAdditional);
    }

    /**
     * @return the index of the "more results" entry, or if there is no "more results" entry,
     *   something large enough so that the index will never be requested (e.g the size).
     */
    private int snapshotSuggestionsInternal(
            ArrayList<SuggestionData> dest, boolean expandAdditional) {
        dest.clear();

        // Add 'go to website' right at top if applicable.
        if (mGoToWebsiteSuggestion != null) {
            if (DBG) Log.d(TAG, "snapshot: adding 'go to website'");
            dest.add(mGoToWebsiteSuggestion);
        }

        // start with all shortcuts
        dest.addAll(mShortcuts);

        final int promotedSlotsAvailable = mMaxPromotedSlots - mShortcuts.size();
        final int chunkSize = mPromotedSources.isEmpty() ?
                0 :
                Math.max(1, promotedSlotsAvailable / mPromotedSources.size());

        // grab reported results from promoted sources that reported before the deadline
        ArrayList<Iterator<SuggestionData>> reportedResults =
                new ArrayList<Iterator<SuggestionData>>(mReportedResults.size());
        for (SuggestionResult suggestionResult : mReportedResults.values()) {
            final ComponentName name = suggestionResult.getSource().getComponentName();
            if (mPromotedSources.contains(name)
                    && mReportedBeforeDeadline.contains(name)
                    && !suggestionResult.getSuggestions().isEmpty()) {
                reportedResults.add(suggestionResult.getSuggestions().iterator());
            }
        }

        HashMap<ComponentName, Integer> sourceToNumDisplayed =
                new HashMap<ComponentName, Integer>();

        // fill in chunk size
        int numSlotsUsed = 0;
        for (Iterator<SuggestionData> reportedResult : reportedResults) {
            for (int i = 0; i < chunkSize; i++) {
                if (reportedResult.hasNext()) {
                    final SuggestionData suggestionData = reportedResult.next();
                    if (!isDupeOfShortcut(suggestionData)) {
                        dest.add(suggestionData);
                        final Integer displayed =
                                sourceToNumDisplayed.get(suggestionData.getSource());
                        sourceToNumDisplayed.put(
                            suggestionData.getSource(), displayed == null ? 1 : displayed + 1);
                        numSlotsUsed++;
                    }
                } else {
                    break;
                }
            }
        }

        // if all of the promoted sources have responded (or the deadline for promoted sources
        // has passed), we use up any remaining promoted slots, and display the "more" UI
        // - one exception: shortcuts only (no sources)
        final boolean pastDeadline = isPastDeadline();
        final boolean allPromotedResponded = mReportedResults.size() >= mPromotedSources.size();
        mShowingMore = (pastDeadline || allPromotedResponded) && !mSources.isEmpty();
        if (mShowingMore) {

            if (DBG) Log.d(TAG, "snapshot: mixing in rest of results.");

            // prune out results that have nothing left
            final Iterator<Iterator<SuggestionData>> pruner = reportedResults.iterator();
            while (pruner.hasNext()) {
                Iterator<SuggestionData> suggestionDataIterator = pruner.next();
                if (!suggestionDataIterator.hasNext()) {
                    pruner.remove();
                }
            }

            // fill in remaining promoted slots, keep track of how many results from each
            // source have been displayed
            int slotsRemaining = promotedSlotsAvailable - numSlotsUsed;
            final int newChunk = reportedResults.isEmpty() ?
                    0 : Math.max(1, slotsRemaining / reportedResults.size());
            for (Iterator<SuggestionData> reportedResult : reportedResults) {
                if (slotsRemaining <= 0) break;
                for (int i = 0; i < newChunk && slotsRemaining > 0; i++) {
                    if (reportedResult.hasNext()) {
                        final SuggestionData suggestionData = reportedResult.next();
                        if (!isDupeOfShortcut(suggestionData)) {
                            dest.add(suggestionData);
                            final Integer displayed =
                                    sourceToNumDisplayed.get(suggestionData.getSource());
                            sourceToNumDisplayed.put(
                            suggestionData.getSource(), displayed == null ? 1 : displayed + 1);
                            slotsRemaining--;
                        }
                    } else {
                        break;
                    }
                }
            }

            // gather stats about sources so we can properly construct "more" ui
            ArrayList<SourceStat> moreSources = new ArrayList<SourceStat>();
            for (SuggestionSource source : mSources) {
                final boolean promoted = mPromotedSources.contains(source.getComponentName());
                final boolean reported = mReportedResults.containsKey(source.getComponentName());
                final boolean beforeDeadline =
                        mReportedBeforeDeadline.contains(source.getComponentName());

                if (!reported) {
                    // sources that haven't reported yet
                    moreSources.add(new SourceStat(
                            source.getComponentName(), promoted, source.getLabel(),
                            source.getIcon(), false, 0, 0));
                } else if (beforeDeadline && promoted) {
                    // promoted sources that have reported before the deadline are only in "more"
                    // if they have undisplayed results
                    final SuggestionResult sourceResult =
                            mReportedResults.get(source.getComponentName());
                    int numDisplayed = sourceToNumDisplayed.containsKey(source.getComponentName())
                            ? sourceToNumDisplayed.get(source.getComponentName()) : 0;

                    if (numDisplayed < sourceResult.getSuggestions().size()) {
                        // Decrement the number of results remaining by one if one of them
                        // is a pin-to-bottom suggestion from the web search source.
                        int numResultsRemaining = sourceResult.getCount() - numDisplayed;
                        int queryLimit = sourceResult.getQueryLimit() - numDisplayed;
                        if (mPinToBottomSuggestion != null && isWebSuggestionSource(source)) {
                            numResultsRemaining--;
                            queryLimit--;
                        }
                        
                        moreSources.add(
                                new SourceStat(
                                        source.getComponentName(),
                                        promoted,
                                        source.getLabel(),
                                        source.getIcon(),
                                        true,
                                        numResultsRemaining,
                                        queryLimit));
                    }
                } else {
                    // unpromoted sources that have reported
                    final SuggestionResult sourceResult =
                            mReportedResults.get(source.getComponentName());
                    moreSources.add(
                            new SourceStat(
                                    source.getComponentName(),
                                    false,
                                    source.getLabel(),
                                    source.getIcon(),
                                    true,
                                    sourceResult.getCount(),
                                    sourceResult.getQueryLimit()));
                }
            }

            // add "search the web"
            if (mSearchTheWebSuggestion != null) {
                if (DBG) Log.d(TAG, "snapshot: adding 'search the web'");
                dest.add(mSearchTheWebSuggestion);
            }

            // add "more results" if applicable
            int indexOfMore = dest.size();
            if (!moreSources.isEmpty()) {
                if (DBG) Log.d(TAG, "snapshot: adding 'more results' expander");

                dest.add(mMoreFactory.getMoreEntry(expandAdditional, moreSources));
                if (expandAdditional) {
                    for (SourceStat moreSource : moreSources) {
                        if (DBG) Log.d(TAG, "snapshot: adding 'more' " + moreSource.getLabel());
                        dest.add(mCorpusFactory.getCorpusEntry(moreSource));
                    }
                }
            }
            
            // add a pin-to-bottom suggestion if one has been found to use
            if (mPinToBottomSuggestion != null) {
                if (DBG) Log.d(TAG, "snapshot: adding a pin-to-bottom suggestion");
                dest.add(mPinToBottomSuggestion);
            }
            
            return indexOfMore;
        }
        return dest.size();
    }

    private boolean isDupeOfShortcut(SuggestionData suggestion) {
        return mShortcutIntentKeys.contains(makeSuggestionKey(suggestion));
    }

    private String makeSuggestionKey(SuggestionData suggestion) {
        return suggestion.getIntentAction() + "#" + suggestion.getIntentData();
    }

    @Override
    protected synchronized boolean addSourceResults(SuggestionResult suggestionResult) {
        final SuggestionSource source = suggestionResult.getSource();
        
        // If the source is the web search source and there is a pin-to-bottom suggestion at
        // the end of the list of suggestions, store it separately, remove it from the list,
        // and keep going. The stored suggestion will be added to the very bottom of the list
        // in snapshotSuggestions.
        if (isWebSuggestionSource(source)) {
            List<SuggestionData> suggestions = suggestionResult.getSuggestions();
            if (!suggestions.isEmpty()) {
                int lastPosition = suggestions.size() - 1;
                SuggestionData lastSuggestion = suggestions.get(lastPosition);
                if (lastSuggestion.isPinToBottom()) {
                    mPinToBottomSuggestion = lastSuggestion;
                    suggestions.remove(lastPosition);
                }
            }
        }
        
        mReportedResults.put(source.getComponentName(), suggestionResult);
        final boolean pastDeadline = isPastDeadline();
        if (!pastDeadline) {
            mReportedBeforeDeadline.add(source.getComponentName());
        }
        return pastDeadline || !suggestionResult.getSuggestions().isEmpty();
    }
    
    /**
     * Compares the provided source to the selected web search source.
     */
    private boolean isWebSuggestionSource(SuggestionSource source) {
        return source.getComponentName().equals(mSelectedWebSearchSource.getComponentName());
    }

    @Override
    protected synchronized boolean refreshShortcut(
            ComponentName source, String shortcutId, SuggestionData refreshed) {
        // don't do anything in the removal case
        if (refreshed == null) return false;

        final int size = mShortcuts.size();
        for (int i = 0; i < size; i++) {
            final SuggestionData shortcut = mShortcuts.get(i);
            if (shortcutId.equals(shortcut.getShortcutId())) {
                mShortcuts.set(i, refreshed);
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized boolean isResultsPending() {
        return mReportedResults.size() < mPromotedSources.size();
    }

    @Override
    public boolean isShowingMore() {
        return mShowingMore;
    }

    @Override
    public int getMoreResultPosition() {
        return mIndexOfMore;
    }

    /**
     * Stats about a particular source that includes enough information to properly display
     * "more results" entries.
     */
    static class SourceStat {
        private final ComponentName mName;
        private final boolean mShowingPromotedResults;
        private final String mLabel;
        private final String mIcon;
        private final boolean mResponded;
        private final int mNumResults;
        private final int mQueryLimit;

        /**
         * @param name The component name of the source.
         * @param showingPromotedResults Whether this source has anything showing in the promoted
         *        slots.
         * @param label The label.
         * @param icon The icon.
         * @param responded Whether it has responded.
         * @param numResults The number of results (if applicable).
         * @param queryLimit The number of results requested from the source.
         */
        SourceStat(ComponentName name, boolean showingPromotedResults, String label, String icon,
                   boolean responded, int numResults, int queryLimit) {
            this.mName = name;
            mShowingPromotedResults = showingPromotedResults;
            this.mLabel = label;
            this.mIcon = icon;
            this.mResponded = responded;
            this.mNumResults = numResults;
            mQueryLimit = queryLimit;
        }

        public ComponentName getName() {
            return mName;
        }

        public boolean isShowingPromotedResults() {
            return mShowingPromotedResults;
        }

        public String getLabel() {
            return mLabel;
        }

        public String getIcon() {
            return mIcon;
        }

        public boolean isResponded() {
            return mResponded;
        }

        public int getNumResults() {
            return mNumResults;
        }

        public int getQueryLimit() {
            return mQueryLimit;
        }
    }
}
