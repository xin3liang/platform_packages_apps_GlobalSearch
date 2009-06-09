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
import android.database.AbstractCursor;
import android.database.Cursor;
import android.database.CursorIndexOutOfBoundsException;
import android.database.CursorWindow;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import com.android.internal.database.ArrayListCursor;

import static android.app.SearchManager.DialogCursorProtocol;

import java.util.ArrayList;
import java.util.List;

/**
 * This is the Cursor that we return from SuggestionProvider.  It relies on its
 * {@link SuggestionBacker} for results and notifications of changes to the results.
 *
 * Important: a local consistent copy of the suggestions is stored in the cursor.  The only safe
 * place to update this copy is in {@link #requery}.
 */
public class SuggestionCursor extends AbstractCursor implements SuggestionBacker.Listener {
    // set to true to enable the more verbose debug logging for this file
    private static final boolean DBG = false;

    // set to true along with DBG to be even more verbose
    private static final boolean SPEW = false;

    private static final String TAG = SuggestionCursor.class.getSimpleName();

    // the same as the string in suggestActionMsgColumn in res/xml/searchable.xml
    private static final String SUGGEST_COLUMN_ACTION_MSG_CALL = "suggest_action_msg_call";
    private boolean mOnMoreCalled = false;

    /**
     * The columns of this cursor, and their associated properties
     */
    enum Column {
        Id("_id"),
        Format(SearchManager.SUGGEST_COLUMN_FORMAT),
        Text1(SearchManager.SUGGEST_COLUMN_TEXT_1),
        Text2(SearchManager.SUGGEST_COLUMN_TEXT_2),
        Icon1(SearchManager.SUGGEST_COLUMN_ICON_1),
        Icon2(SearchManager.SUGGEST_COLUMN_ICON_2),
        Query(SearchManager.SUGGEST_COLUMN_QUERY),
        IntentAction(SearchManager.SUGGEST_COLUMN_INTENT_ACTION),
        IntentData(SearchManager.SUGGEST_COLUMN_INTENT_DATA),
        ActionMsgCall(SUGGEST_COLUMN_ACTION_MSG_CALL),
        IntentExtraData(SearchManager.SUGGEST_COLUMN_INTENT_EXTRA_DATA),
        ShortcutId(SearchManager.SUGGEST_COLUMN_SHORTCUT_ID),
        BackgroundColor(SearchManager.SUGGEST_COLUMN_BACKGROUND_COLOR);

        public final String name;

        Column(String name) {
            this.name = name;
        }

        static String[] NAMES = initNames();

        static String[] initNames() {
            final Column[] columns = Column.values();
            final String[] names = new String[columns.length];
            for (int i = 0; i < columns.length; i++) {
                names[i] = columns[i].name;
            }
            return names;
        }
    }

    /**
     * @return An empty cursor with the appropriate columns.
     */
    static Cursor makeEmptyCursor() {
        // note: it is not safe to cache one single static cursor somewhere, that leads
        // to memory leaks, as the cursor may end up holding onto pieces of the UI framework
        return new ArrayListCursor(Column.NAMES, new ArrayList<ArrayList>());
    }

    private final String mQuery;
    private final Handler mHandler;
    private boolean mIncludeSources;
    private CursorListener mListener;

    private final SuggestionBacker mBacker;
    private long mNextNotify = 0;

    // we keep a consistent snapshot locally
    private ArrayList<SuggestionData> mData = new ArrayList<SuggestionData>(10);

    /**
     * We won't call {@link AbstractCursor#onChange} more than once per window.
     */
    private static final int CURSOR_NOTIFY_WINDOW_MS = 100;

    /**
     * Interface for receiving notification from the cursor.
     */
    public interface CursorListener {
        /**
         * Called when the cursor has been closed.
         *
         * @param displayedSuggestions the suggestions that have been displayed to the user.
         */
        void onClose(List<SuggestionData> displayedSuggestions);

        /**
         * Called when an item is clicked.
         *
         */
        void onItemClicked(SuggestionData clicked);

        /**
         * Called the first time "more" becomes visible
         */
        void onMoreVisible();
    }

    /**
     * @param suggestionBacker Holds the incoming results
     * @param handler used to post messages.
     * @param query The query that was sent.
     * @param includeSources whether to include corpus selection suggestions.
     */
    public SuggestionCursor(SuggestionBacker suggestionBacker, Handler handler, String query,
            boolean includeSources) {
        mBacker = suggestionBacker;
        mQuery = query;
        mHandler = handler;
        mIncludeSources = includeSources;
        suggestionBacker.snapshotSuggestions(mData, mIncludeSources);
    }

    /**
     * Prefills the results from this cursor with the results from another.  This is used when no
     * other results are initially available to provide a smoother experience.
     *
     * @param other The other cursor to get the results from.
     */
    public void prefill(SuggestionCursor other) {
        if (!mData.isEmpty()) {
            throw new IllegalStateException("prefilled when we aleady have results");
        }
        mData.clear();
        mData.addAll(other.mData);
    }

    @Override
    public String[] getColumnNames() {
        return Column.NAMES;
    }

    @Override
    public int getCount() {
        return mData.size();
    }

    /**
     * Handles out-of-band messages from the search dialog.
     */
    @Override
    public Bundle respond(Bundle extras) {

        final int method = extras.getInt(SearchManager.DialogCursorProtocol.METHOD, -1);

        if (method == -1) {
            Log.w(TAG, "received unexpectd respond: no DialogCursorProtocol.METHOD specified.");
            return Bundle.EMPTY;
        }

        switch (method) {
            case DialogCursorProtocol.POST_REFRESH:
                return respondPostRefresh(extras);
            case DialogCursorProtocol.PRE_CLOSE:
                return respondPreClose(extras);
            case DialogCursorProtocol.CLICK:
                return respondClick(extras);
            case DialogCursorProtocol.THRESH_HIT:
                return respondThreshHit(extras);
            default:
                Log.e(TAG, "unexpected DialogCursorProtocol.METHOD " + method);
                return Bundle.EMPTY;
        }
    }

    /**
     * Handle receiving and sending back information associated with
     * {@link DialogCursorProtocol#POST_REFRESH}.
     *
     * @param request The bundle sent.
     * @return The response bundle.
     */
    private Bundle respondPostRefresh(Bundle request) {
        Bundle response = new Bundle(2);
        response.putBoolean(
                DialogCursorProtocol.POST_REFRESH_RECEIVE_ISPENDING, mBacker.isResultsPending());

        if (mBacker.isShowingMore() && !mOnMoreCalled) {
            // tell the dialog we want to be notified when "more results" is first displayed
            response.putInt(
                    DialogCursorProtocol.POST_REFRESH_RECEIVE_DISPLAY_NOTIFY,
                    mBacker.getMoreResultPosition());
        }
        return response;
    }

    /**
     * Handle receiving and sending back information associated with
     * {@link DialogCursorProtocol#PRE_CLOSE}.
     *
     * @param request The bundle sent.
     * @return The response bundle.
     */
    private Bundle respondPreClose(Bundle request) {
        int maxPosDisplayed =
                request.getInt(DialogCursorProtocol.PRE_CLOSE_SEND_MAX_DISPLAY_POS, -1);

        if (maxPosDisplayed >= mData.size()) {
            maxPosDisplayed = -1;  // impressions of prefilled data :(
        }
        if (mListener != null) {
            mListener.onClose(mData.subList(0, maxPosDisplayed + 1));
        }
        return Bundle.EMPTY;
    }

    /**
     * Handle receiving and sending back information associated with
     * {@link DialogCursorProtocol#CLICK}.
     *
     * @param request The bundle sent.
     * @return The response bundle.
     */
    private Bundle respondClick(Bundle request) {
        final int pos = request.getInt(DialogCursorProtocol.CLICK_SEND_POSITION, -1);
        if (pos == -1) {
            Log.w(TAG, "DialogCursorProtocol.CLICK didn't come with extra CLICK_SEND_POSITION");
            return Bundle.EMPTY;
        }

        if (mListener != null) mListener.onItemClicked(mData.get(pos));

        // if they click on the "more results item"
        if (pos == mBacker.getMoreResultPosition()) {
            // toggle the expansion of the addditional sources
            mIncludeSources = !mIncludeSources;
            onNewResults();

            if (mIncludeSources) {
                // if we have switched to expanding,
                // tell the search dialog to select the position of the "more" entry so that
                // the additional corpus entries will become visible without having to
                // manually scroll
                final Bundle response = new Bundle();
                response.putInt(DialogCursorProtocol.CLICK_RECEIVE_SELECTED_POS, pos);
                return response;
            }
        }
        return Bundle.EMPTY;
    }

    /**
     * Handle receiving and sending back information associated with
     * {@link DialogCursorProtocol#THRESH_HIT}.
     *
     * We use this to get notified when "more" is first scrolled onto screen.
     *
     * @param request The bundle sent.
     * @return The response bundle.
     */
    private Bundle respondThreshHit(Bundle request) {
        mOnMoreCalled = true;
        if (mListener != null) mListener.onMoreVisible();
        return Bundle.EMPTY;
    }

    /**
     * We don't copy over a fresh copy of the data, instead, we notify the cursor that the
     * data has changed, and wait to for {@link #requery} to be called.  This way, any
     * adapter backed by this cursor will have a consistent view of the data, and {@link #requery}
     * us when ready.
     *
     * Calls {@link AbstractCursor#onChange} only if there isn't already one planned to be called
     * within {@link #CURSOR_NOTIFY_WINDOW_MS}.
     *
     * {@inheritDoc}
     */
    public synchronized void onNewResults() {
        if (DBG) Log.d(TAG, "onNewResults()");
        if (!isClosed()) {
            long now = SystemClock.uptimeMillis();
            if (now < mNextNotify) {
                if (DBG) Log.d(TAG, "-avoided a notify!");
                return;
            }
            mNextNotify = now + CURSOR_NOTIFY_WINDOW_MS;

            if (DBG) Log.d(TAG, "-posting onChange(false)");
            mHandler.postAtTime(mNotifier, mNextNotify);
        }
    }

    private final Runnable mNotifier = new Runnable() {
        public void run() {
            SuggestionCursor.this.onChange(false);
        }
    };

    /**
     * Gets the current suggestion.
     */
    private SuggestionData get() {
        if (mPos < 0) {
            throw new CursorIndexOutOfBoundsException("Before first row.");
        }
        if (mPos >= mData.size()) {
            throw new CursorIndexOutOfBoundsException("After last row.");
        }

        SuggestionData suggestion = mData.get(mPos);
        if (DBG && SPEW) Log.d(TAG, "get(" + mPos + ")");
        if (DBG && SPEW) Log.d(TAG, suggestion.toString());
        return suggestion;
    }

    @Override
    public boolean requery() {
        if (DBG) Log.d(TAG, "requery()");
        mBacker.snapshotSuggestions(mData, mIncludeSources);
        return super.requery();
    }

    @Override
    public double getDouble(int column) {
        return Double.valueOf(getString(column));
    }

    @Override
    public float getFloat(int column) {
        return Float.valueOf(getString(column));
    }

    @Override
    public int getInt(int column) {
        return Integer.valueOf(getString(column));
    }

    @Override
    public long getLong(int column) {
        return Long.valueOf(getString(column));
    }

    @Override
    public short getShort(int column) {
        return Short.valueOf(getString(column));
    }


    @Override
    public String getString(int columnIndex) {
        if (DBG && SPEW) Log.d(TAG, "getString(columnIndex=" + columnIndex + ")");
        return (String) getColumnValue(get(), columnIndex);
    }

    private Object getColumnValue(SuggestionData suggestion, int columnIndex) {
        final Column column = getColumn(columnIndex);
        switch(column) {
            case Id: return String.valueOf(mPos);
            case Format: return suggestion.getFormat();
            case Text1: return suggestion.getTitle();
            case Text2: return suggestion.getDescription();
            case Icon1: return suggestion.getIcon1();
            case Icon2: return suggestion.getIcon2();
            case Query: return suggestion.getIntentQuery();
            case IntentAction: return suggestion.getIntentAction();
            case IntentData: return suggestion.getIntentData();
            case ActionMsgCall: return suggestion.getActionMsgCall();
            case IntentExtraData: return suggestion.getIntentExtraData();
            case ShortcutId: return suggestion.getShortcutId();
            case BackgroundColor: return Integer.toString(suggestion.getBackgroundColor());
            default:
                throw new RuntimeException("we musta forgot about one of the columns :-/");
        }
    }

    private Column getColumn(int columnIndex) {
        Column column = null;
        final Column[] columns = Column.values();
        try {
            column = columns[columnIndex];
        } catch (ArrayIndexOutOfBoundsException e) {
            throw new CursorIndexOutOfBoundsException("Requested column: "
                    + column + ", # of columns: " +  columns.length);
        }
        return column;
    }

    @Override
    public boolean isNull(int column) {
        return getString(column) == null;
    }

    /**
     * Sets the listener which will be notified if the "more results" entry is shown, and when
     * the cursor has been closed.
     *
     * @param listener The listener. May be <code>null</code> to remove
     * the current listener.
     */
    public void setListener(CursorListener listener) {
        mListener = listener;
    }
}
