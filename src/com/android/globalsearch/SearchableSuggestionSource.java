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

import android.app.SearchManager;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.server.search.SearchableInfo;
import android.util.Log;
import android.view.KeyEvent;

import java.util.ArrayList;

/**
 * Suggestion source that uses the {@link SearchableInfo} of a given component
 * to get suggestions.
 */
public class SearchableSuggestionSource extends AbstractSuggestionSource {

    private static final boolean DBG = false;
    private static final String LOG_TAG = SearchableSuggestionSource.class.getSimpleName();

    private Context mContext;

    private SearchableInfo mSearchable;

    private ActivityInfo mActivityInfo;

    // Cached label for the activity
    private String mLabel;

    // Cached icon for the activity
    private String mIcon;
    
    // An override value for the max number of results to provide.
    private int mMaxResultsOverride;
    
    // A private column the web search source uses to instruct us to pin a result
    // (like "Manage search history") to the bottom of the list when appropriate.
    private static final String SUGGEST_COLUMN_PIN_TO_BOTTOM = "suggest_pin_to_bottom";

    public SearchableSuggestionSource(Context context, SearchableInfo searchable) {
        mContext = context;
        mSearchable = searchable;

        try {
            mActivityInfo = context.getPackageManager()
                    .getActivityInfo(mSearchable.getSearchActivity(), PackageManager.GET_META_DATA);
        } catch (NameNotFoundException ex) {
            throw new RuntimeException("Searchable activity " + mSearchable.getSearchActivity()
                    + " not found.");
        }
        mLabel = findLabel();
        mIcon = findIcon();
        mMaxResultsOverride = 0;
    }
    
    public SearchableSuggestionSource(Context context, SearchableInfo searchable,
            int maxResultsOverride) {
        this(context, searchable);
        mMaxResultsOverride = maxResultsOverride;
    }

    /**
     * Gets the Context that this suggestion source runs in.
     */
    public Context getContext() {
        return mContext;
    }

    @Override
    public int getQueryThreshold() {
        return mSearchable.getSuggestThreshold();
    }

    /**
     * Gets the localized, human-readable label for this source.
     */
    public String getLabel() {
        return mLabel;
    }

    /**
     * Gets the icon for this suggestion source as an android.resource: URI.
     */
    public String getIcon() {
        return mIcon;
    }

    /**
     * Gets the name of the activity that this source is for.
     */
    public ComponentName getComponentName() {
        return mSearchable.getSearchActivity();
    }

    @Override
    public SuggestionResult getSuggestions(String query, int maxResults, int queryLimit) {
        Cursor cursor = getCursor(query, queryLimit);
        // Be resilient to non-existent suggestion providers, as the build this is running on
        // is not guaranteed to have anything in particular.
        if (cursor == null) return mEmptyResult;
        
        maxResults = (mMaxResultsOverride > 0) ? mMaxResultsOverride : maxResults;

        try {
            ArrayList<SuggestionData> suggestions
                    = new ArrayList<SuggestionData>(cursor.getCount());
            while (cursor.moveToNext() && suggestions.size() < maxResults) {
                if (Thread.interrupted()) {
                    return mEmptyResult;
                }
                SuggestionData suggestion = makeSuggestion(cursor);
                if (suggestion != null) {
                    suggestions.add(suggestion);
                }
            }
            return new SuggestionResult(this, suggestions, cursor.getCount(), queryLimit);
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
    }

    /**
     * Gets a Cursor containing suggestions.
     *
     * @param query The query to get suggestions for.
     * @return A Cursor.
     */
    protected Cursor getCursor(String query, int queryLimit) {
        return getSuggestions(getContext(), mSearchable, query, queryLimit);
    }

    /**
     * This is a copy of {@link SearchManager#getSuggestions(Context, SearchableInfo, String)}.
     * The only difference is that it adds "?limit={maxResults}".
     */
    private static Cursor getSuggestions(Context context, SearchableInfo searchable, String query,
            int queryLimit) {
        if (searchable == null) {
            return null;
        }

        String authority = searchable.getSuggestAuthority();
        if (authority == null) {
            return null;
        }

        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority);

        // if content path provided, insert it now
        final String contentPath = searchable.getSuggestPath();
        if (contentPath != null) {
            uriBuilder.appendEncodedPath(contentPath);
        }

        // append standard suggestion query path
        uriBuilder.appendPath(SearchManager.SUGGEST_URI_PATH_QUERY);

        // get the query selection, may be null
        String selection = searchable.getSuggestSelection();
        // inject query, either as selection args or inline
        String[] selArgs = null;
        if (selection != null) {    // use selection if provided
            selArgs = new String[] { query };
        } else {                    // no selection, use REST pattern
            uriBuilder.appendPath(query);
        }

        uriBuilder.appendQueryParameter("limit", String.valueOf(queryLimit));

        Uri uri = uriBuilder
                .fragment("")  // TODO: Remove, workaround for a bug in Uri.writeToParcel()
                .build();

        // finally, make the query
        return context.getContentResolver().query(uri, null, selection, selArgs, null);
    }

    @Override
    protected SuggestionData validateShortcut(String shortcutId) {
        Cursor cursor = getValidationCursor(shortcutId);

        if (cursor == null) return null;

        int count = cursor.getCount();
        if (count == 0) return null;

        if (count > 1) {
            Log.w(LOG_TAG, "received " + count + " results for validation of a single shortcut");
        }
        cursor.moveToNext();
        return makeSuggestion(cursor);
    }

    protected Cursor getValidationCursor(String shortcutId) {

        String authority = mSearchable.getSuggestAuthority();
        if (authority == null) {
            return null;
        }

        Uri.Builder uriBuilder = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(authority);

        // if content path provided, insert it now
        final String contentPath = mSearchable.getSuggestPath();
        if (contentPath != null) {
            uriBuilder.appendEncodedPath(contentPath);
        }

        // append the shortcut path and id
        uriBuilder.appendPath(SearchManager.SUGGEST_URI_PATH_SHORTCUT);
        uriBuilder.appendPath(shortcutId);

        Uri uri = uriBuilder
                .query("")     // TODO: Remove, workaround for a bug in Uri.writeToParcel()
                .fragment("")  // TODO: Remove, workaround for a bug in Uri.writeToParcel()
                .build();

        // finally, make the query
        return getContext().getContentResolver().query(uri, null, null, null, null);
    }

    /**
     * Builds a suggestion for the current entry in the cursor.
     * The default implementation calls {@link #getTitle(Cursor)} and friends.
     * Subclasses may override this method if overriding the methods for
     * the individual fields is not enough.
     *
     * @return A suggestion, or <code>null</code> if no suggestion can be made
     * from the current record.
     */
    protected SuggestionData makeSuggestion(Cursor cursor) {
        String format = getFormat(cursor);
        String title = getTitle(cursor);
        String description = getDescription(cursor);
        if (description == null) {
            description = "";
        }
        String icon1 = getIcon1(cursor);
        String icon2 = getIcon2(cursor);
        String intentAction = getIntentAction(cursor);
        if (intentAction == null) {
            intentAction = Intent.ACTION_DEFAULT;
        }
        String intentData = getIntentData(cursor);
        String query = getQuery(cursor);
        String actionMsgCall = getActionMsgCall(cursor);
        String intentExtraData = getIntentExtraData(cursor);
        // The following overwrites any value provided by the searchable since we only direct
        // intents provided by third-party searchables to that searchable activity.
        String intentComponentName = getComponentName().flattenToShortString();
        String shortcutId = getShortcutId(cursor);
        boolean pinToBottom = isPinToBottom(cursor);
        boolean spinnerWhileRefreshing = isSpinnerWhileRefreshing(cursor);

        return new SuggestionData.Builder(getComponentName())
                .format(format)
                .title(title)
                .description(description)
                .icon1(icon1)
                .icon2(icon2)
                .intentAction(intentAction)
                .intentData(intentData)
                .intentQuery(query)
                .actionMsgCall(actionMsgCall)
                .intentExtraData(intentExtraData)
                .intentComponentName(intentComponentName)
                .shortcutId(shortcutId)
                .pinToBottom(pinToBottom)
                .spinnerWhileRefreshing(spinnerWhileRefreshing)
                .build();
    }

    /**
     * Gets the text format.
     *
     * @return The value of the optional {@link SearchManager#SUGGEST_COLUMN_FORMAT} column,
     *         or <code>null</code> if the cursor does not contain that column.
     */
    protected String getFormat(Cursor cursor) {
        return getColumnString(cursor, SearchManager.SUGGEST_COLUMN_FORMAT);
    }

    /**
     * Gets the text to put in the first line of the suggestion for the current entry.
     * Subclasses may want to override this to provide better titles.
     *
     * @return The value of the required {@link SearchManager#SUGGEST_COLUMN_TEXT_1} column.
     */
    protected String getTitle(Cursor cursor) {
        return getColumnString(cursor, SearchManager.SUGGEST_COLUMN_TEXT_1);
    }

    /**
     * Gets the text to put in the second line of the suggestion for the current entry.
     * Subclasses may want to override this to provide better descriptions.
     *
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_TEXT_1} column.
     * or <code>null</code> if the cursor does not contain that column.
     */
    protected String getDescription(Cursor cursor) {
        return getColumnString(cursor, SearchManager.SUGGEST_COLUMN_TEXT_2);
    }

    /**
     * Gets the first icon for the current entry (displayed on the left side of
     * the suggestion). This should be a string containing the resource ID or URI
     * of a {@link Drawable}.
     *
     * If no icon was provided in the cursor, the default icon for this searchable
     * (provided by {@link #getIcon()}) will be used.
     *
     * Subclasses may want to override this to provide better icons.
     *
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_ICON_1} column,
     * or the value of {@link #getIcon()} if the cursor does not contain that column.
     */
    protected String getIcon1(Cursor cursor) {
        // Get the icon provided in the cursor. If none, get the source's icon.
        String icon = getIcon(cursor, SearchManager.SUGGEST_COLUMN_ICON_1);
        if (icon == null) {
            icon = getIcon();  // the app's icon
        }
        return icon;
    }

    /**
     * Gets the second icon for the current entry (displayed on the right side of
     * the suggestion). This should be a string containing the resource ID or URI
     * of a {@link Drawable}.
     *
     * Subclasses may want to override this to provide better icons.
     *
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_ICON_2} column,
     * or <code>null</code> if the cursor does not contain that column.
     */
    protected String getIcon2(Cursor cursor) {
        return getIcon(cursor, SearchManager.SUGGEST_COLUMN_ICON_2);
    }

    /**
     * Gets an icon URI from a cursor. If the cursor returns a resource ID,
     * this is converted into an android.resource:// URI.
     */
    protected String getIcon(Cursor cursor, String columnName) {
        String icon = getColumnString(cursor, columnName);
        if (icon == null || icon.length() == 0 || "0".equals(icon)) {
            // SearchManager specifies that null or zero can be returned to indicate
            // no icon. We also allow empty string.
            return null;
        } else if (!Character.isDigit(icon.charAt(0))){
            return icon;
        } else {
            String packageName = getComponentName().getPackageName();
            return new Uri.Builder()
                    .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                    .authority(packageName)
                    .encodedPath(icon)
                    .toString();
        }
    }

    /**
     * Gets the intent action for the current entry.
     */
    protected String getIntentAction(Cursor cursor) {
        String intentAction = getColumnString(cursor, SearchManager.SUGGEST_COLUMN_INTENT_ACTION);
        if (intentAction == null) {
            intentAction = mSearchable.getSuggestIntentAction();
        }
        return intentAction;
    }

    /**
     * Gets the intent data for the current entry. This includes the value of
     * {@link SearchManager#SUGGEST_COLUMN_INTENT_DATA_ID}.
     */
    protected String getIntentData(Cursor cursor) {
        String intentData = getColumnString(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA);
        if (intentData == null) {
            intentData = mSearchable.getSuggestIntentData();
        }
        if (intentData == null) {
            return null;
        }
        String intentDataId = getColumnString(cursor, SearchManager.SUGGEST_COLUMN_INTENT_DATA_ID);
        return intentDataId == null ? intentData : intentData + "/" + Uri.encode(intentDataId);
    }

    /**
     * Gets the intent extra data for the current entry.
     *
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_INTENT_EXTRA_DATA} column,
     * or <code>null</code> if the cursor does not contain that column.
     */
    protected String getIntentExtraData(Cursor cursor) {
        return getColumnString(cursor, SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA);
    }

    /**
     * Gets the search query for the current entry.
     *
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_QUERY} column,
     * or <code>null</code> if the cursor does not contain that column.
     */
    protected String getQuery(Cursor cursor) {
        return getColumnString(cursor, SearchManager.SUGGEST_COLUMN_QUERY);
    }

    /**
     * Gets the shortcut id for the current entry.
     *
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_SHORTCUT_ID} column,
     * or <code>null</code> if the cursor does not contain that column.
     */
    protected String getShortcutId(Cursor cursor) {
        return getColumnString(cursor, SearchManager.SUGGEST_COLUMN_SHORTCUT_ID);
    }
    
    /**
     * Determines whether this suggestion is a pin-to-bottom suggestion.
     * 
     * @return The value of the {@link #SUGGEST_COLUMN_PIN_TO_BOTTOM} column, or
     * <code>false</code> if the cursor does not contain that column.
     */
    protected boolean isPinToBottom(Cursor cursor) {
        return "true".equals(getColumnString(cursor, SUGGEST_COLUMN_PIN_TO_BOTTOM));
    }
    
    /**
     * Determines whether this suggestion should show a spinner while refreshing.
     * 
     * @return The value of the {@link SearchManager#SUGGEST_COLUMN_SPINNER_WHILE_REFRESHING}
     * column, or <code>false</code> if the cursor does not contain that column.
     */
    protected boolean isSpinnerWhileRefreshing(Cursor cursor) {
        return "true".equals(
                getColumnString(cursor, SearchManager.SUGGEST_COLUMN_SPINNER_WHILE_REFRESHING));
    }

    /**
     * Gets the action message for the CALL key for the current entry.
     */
    protected String getActionMsgCall(Cursor cursor) {
        SearchableInfo.ActionKeyInfo actionKey = mSearchable.findActionKey(KeyEvent.KEYCODE_CALL);
        if (actionKey == null) {
            return null;
        }
        String suggestActionMsg = null;
        String suggestActionMsgCol = actionKey.getSuggestActionMsgColumn();
        if (suggestActionMsgCol != null) {
            suggestActionMsg = getColumnString(cursor, suggestActionMsgCol);
        }
        return suggestActionMsg != null ? suggestActionMsg : actionKey.getSuggestActionMsg();
    }

    private String findLabel() {
        CharSequence label = null;
        PackageManager pm = getContext().getPackageManager();
        // First try the activity label
        int labelRes = mActivityInfo.labelRes;
        if (labelRes != 0) {
            try {
                Resources resources = pm.getResourcesForApplication(mActivityInfo.applicationInfo);
                label = resources.getString(labelRes);
            } catch (NameNotFoundException ex) {
                // shouldn't happen, but if it does, let label remain null
            }
        }
        // Fall back to the application label
        if (label == null) {
            label = pm.getApplicationLabel(mActivityInfo.applicationInfo);
            if (DBG) Log.d(LOG_TAG, getComponentName() + " application label = " + label);
        }
        if (label == null) {
            return null;
        }
        return label.toString();
    }

    private String findIcon() {
        // Try the activity or application icon.
        int iconId = mActivityInfo.getIconResource();
        if (DBG) Log.d(LOG_TAG, getComponentName() + " activity icon = " + iconId);
        // No icon, use default activity icon
        if (iconId == 0) {
            iconId = android.R.drawable.sym_def_app_icon;
        }
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(mSearchable.getSearchActivity().getPackageName())
                .encodedPath(String.valueOf(iconId))
                .toString();
    }

    @Override
    public String toString() {
        return super.toString() + "{component=" + getComponentName().flattenToShortString() + "}";
    }

    /**
     * Gets the value of a string column by name.
     *
     * @param cursor Cursor to read the value from.
     * @param columnName The name of the column to read.
     * @return The value of the given column, or <code>null</null>
     * if the cursor does not contain the given column.
     */
    protected static String getColumnString(Cursor cursor, String columnName) {
        int col = cursor.getColumnIndex(columnName);
        if (col == -1) {
            return null;
        }
        return cursor.getString(col);
    }

    /**
     * Factory method. Creates a suggestion source from the searchable
     * information of a given component.
     *
     * @param context Context to use in the suggestion source.
     * @param componentName Component whose searchable information will be
     * used to construct ths suggestion source.
     * @return A suggestion source, or <code>null</code> if the given component
     * is not searchable.
     */
    public static SearchableSuggestionSource create(Context context, ComponentName componentName) {
        SearchableInfo si = SearchManager.getSearchableInfo(componentName, false);
        if (si == null) {
            return null;
        }
        return new SearchableSuggestionSource(context, si);
    }
    
    /**
     * Factory method. Creates a suggestion source from the searchable
     * information of a given component.
     *
     * @param context Context to use in the suggestion source.
     * @param componentName Component whose searchable information will be
     * used to construct this suggestion source.
     * @param maxResultsOverride An override value to use for the number of results that
     * this source should be allowed to provide.
     * @return A suggestion source, or <code>null</code> if the given component
     * is not searchable.
     */
    public static SearchableSuggestionSource create(Context context, ComponentName componentName,
            int maxResultsOverride) {
        SearchableInfo si = SearchManager.getSearchableInfo(componentName, false);
        if (si == null) {
            return null;
        }
        return new SearchableSuggestionSource(context, si, maxResultsOverride);
    }

    /**
     * Checks whether this source needs to be invoked after an earlier query returned zero results.
     *
     * @return <code>true</code> if this source needs to be invoked after returning zero results.
     */
    public boolean queryAfterZeroResults() {
        return mSearchable.queryAfterZeroResults();
    }

}
