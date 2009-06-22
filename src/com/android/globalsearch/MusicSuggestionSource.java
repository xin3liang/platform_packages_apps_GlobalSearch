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
import android.content.Context;
import android.database.Cursor;
import android.server.search.SearchableInfo;

/**
 * A suggestion source which provides content from the music provider.
 */
public class MusicSuggestionSource extends SearchableSuggestionSource {
    public static final ComponentName MUSIC_COMPONENT =
            new ComponentName("com.android.music", "com.android.music.QueryBrowserActivity");

    public MusicSuggestionSource(Context context, SearchableInfo searchable) {
        super(context, searchable);
    }

    /**
     * Factory method.
     */
    public static MusicSuggestionSource create(Context context) {
        SearchManager searchManager = (SearchManager)
                context.getSystemService(Context.SEARCH_SERVICE);
        SearchableInfo si = searchManager.getSearchableInfo(MUSIC_COMPONENT, false);
        if (si == null) {
            return null;
        }
        return new MusicSuggestionSource(context, si);
    }

    @Override
    public int getQueryThreshold() {
        // TODO: if we decide to include music once and forall, can have music do this in its
        // searchable
        return 3;
    }

    @Override
    protected String getDescription(ColumnCachingCursor cursor) {
        // TODO: Do this in a less hacky way. Probably better to have the MediaProvider return
        //       a type in the 'extra data' column to be read here.
        String contentUri = super.getIntentData(cursor);
        if (contentUri.startsWith("content://media/external/audio/media/")) {
            return getContext().getString(R.string.music_track);
        } else if (contentUri.startsWith("content://media/external/audio/albums/")) {
            return getContext().getString(R.string.music_album);
        } else if (contentUri.startsWith("content://media/external/audio/artists/")) {
            return getContext().getString(R.string.music_artist);
        } else {
            return null;
        }
    }

    @Override
    protected String getIcon1(ColumnCachingCursor cursor) {
        // TODO: Do this in a less hacky way. Probably better to have the MediaProvider return
        //       a type in the 'extra data' column to be read here.
        String contentUri = super.getIntentData(cursor);
        if (contentUri.startsWith("content://media/external/audio/media/")) {
            return String.valueOf(R.drawable.music_song);
        } else if (contentUri.startsWith("content://media/external/audio/albums/")) {
            return String.valueOf(R.drawable.music_album);
        } else if (contentUri.startsWith("content://media/external/audio/artists/")) {
            return String.valueOf(R.drawable.music_artist);
        } else {
            return null;
        }
    }

}
