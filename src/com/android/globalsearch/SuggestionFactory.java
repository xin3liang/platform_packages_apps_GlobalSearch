package com.android.globalsearch;

import android.content.Context;
import android.content.Intent;
import android.content.ComponentName;
import android.content.res.Resources;
import android.app.SearchManager;
import android.text.TextUtils;
import android.text.util.Regex;
import android.util.TypedValue;

import java.util.List;

import static com.android.globalsearch.SourceSuggestionBacker.SourceStat;

/**
 * Contains methods to create special results for the suggestion list such as "search the web",
 * "more results" and the corpus results.
 */
public class SuggestionFactory implements SourceSuggestionBacker.MoreExpanderFactory,
        SourceSuggestionBacker.CorpusResultFactory {

    private static final ComponentName BUILTIN_SOURCE_COMPONENT
            = new ComponentName("com.android.globalsearch",
                    "com.android.globalsearch.GlobalSearch");

    private final Context mContext;
    private final String mQuery;

    // The hex color string to be applied to urls of website suggestions, as derived from
    // the current theme. This is not set until/unless applySearchUrlColor is called,
    // at which point this variable caches the color value.
    private String mSearchUrlColorHex;

    // The background color of the 'more' item and other corpus items.
    private int mCorpusItemBackgroundColor;

    /**
     * @param context The context.
     * @param query The query the results are for.
     */
    public SuggestionFactory(Context context, String query) {
        mContext = context;
        mQuery = query;
        TypedValue colorValue = new TypedValue();
        mContext.getTheme().resolveAttribute(
                        com.android.internal.R.attr.searchWidgetCorpusItemBackground,
                        colorValue, true);
        mCorpusItemBackgroundColor = mContext.getResources().getColor(colorValue.resourceId);
    }

    /** {@inheritDoc} */
    public SuggestionData getMoreEntry(
            boolean expanded, List<SourceSuggestionBacker.SourceStat> sourceStats) {
        StringBuilder desc = new StringBuilder();
        String appSeparator = mContext.getString(R.string.result_count_app_separator);
        int sourceCount = sourceStats.size();

        // TODO: In the code below where we append an extra space after the appSeparator,
        // ideally we'd like to encode this space in the localized
        // string instead. However trailing whitespace is trimmed from strings in strings.xml,
        // so this doesn't work. Figure out how to make that respect whitespace.
        boolean anyPending = false;
        for (int i = 0; i < sourceCount; i++) {
            SourceSuggestionBacker.SourceStat sourceStat = sourceStats.get(i);
            if (sourceStat.getResponseStatus() != SourceStat.ResponseStatus.Finished) {
                anyPending = true;
            }
            int suggestionCount = sourceStat.getNumResults();
            if (suggestionCount > 0) {
                if (desc.length() > 0) {
                    desc.append(appSeparator).append(" ");
                }
                desc.append(sourceStat.getLabel()).append(": ")
                        .append(getCountString(suggestionCount, sourceStat.getQueryLimit()));

            }
        }
        int icon = expanded ? R.drawable.more_results_expanded : R.drawable.more_results;

        final SuggestionData.Builder builder = new SuggestionData.Builder(BUILTIN_SOURCE_COMPONENT)
                .format("html")
                .title("<i>" + mContext.getString(R.string.more_results) + "</i>")
                .description("<i>" + desc.toString() + "</i>")
                .icon1(icon)
                .shortcutId(SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT)
                .backgroundColor(mCorpusItemBackgroundColor)
                .intentAction(SearchManager.INTENT_ACTION_NONE);  // no intent launched for this
        if (anyPending) {
            builder.icon2(com.android.internal.R.drawable.search_spinner);
        }
        return builder.build();
    }

    /**
     * Return a rounded representation of a suggestion count if, e.g. "10+".
     *
     * @param count The number of items.
     * @param limit The maximum number that was requested.
     * @return If the {@code count} is exact, the value of {@code count} is returned.
     *         Otherwise, a rounded valued followed by "+" is returned.
     */
    private String getCountString(int count, int limit) {
        if (limit == 0 || count < limit) {
            return String.valueOf(count);
        } else {
            if (limit > 10) {
                // round to nearest lower multiple of 10
                count = 10 * ((limit - 1) / 10);
            }
            return count + "+";
        }
    }

    /** {@inheritDoc} */
    public SuggestionData getCorpusEntry(SourceSuggestionBacker.SourceStat sourceStat) {
        int suggestionCount = sourceStat.getNumResults();
        final SuggestionData.Builder builder = new SuggestionData.Builder(sourceStat.getName())
                .title(sourceStat.getLabel())
                .shortcutId(SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT)
                .icon1(sourceStat.getIcon())
                .intentAction(SearchManager.INTENT_ACTION_CHANGE_SEARCH_SOURCE)
                .intentData(sourceStat.getName().flattenToString())
                .backgroundColor(mCorpusItemBackgroundColor)
                .intentQuery(mQuery);

        final SourceStat.ResponseStatus responseStatus = sourceStat.getResponseStatus();

        if (responseStatus == SourceStat.ResponseStatus.Finished) {
            final Resources resources = mContext.getResources();
            final String description = sourceStat.isShowingPromotedResults() ?
                    resources.getQuantityString(
                            R.plurals.additional_result_count, suggestionCount, suggestionCount) :
                    resources.getQuantityString(
                            R.plurals.total_result_count, suggestionCount, suggestionCount);
            builder.description(description);            
        }

        if (responseStatus == SourceStat.ResponseStatus.InProgress) {
            builder.icon2(com.android.internal.R.drawable.search_spinner);
        }

        return builder.build();
    }

    /**
     * Creates a one-off suggestion for searching the web with the current query.
     * The description can be a format string with one string value, which will be
     * filled in by the provided query argument.
     */
    public SuggestionData createSearchTheWebSuggestion() {
        if (TextUtils.isEmpty(mQuery)) {
            return null;
        }
        String descriptionFormat = mContext.getString(R.string.search_the_web_description);
        return new SuggestionData.Builder(BUILTIN_SOURCE_COMPONENT)
                .title(mContext.getString(R.string.search_the_web_title))
                .description(String.format(descriptionFormat, mQuery))
                .icon1(R.drawable.magnifying_glass)
                .intentAction(Intent.ACTION_WEB_SEARCH)
                .intentQuery(mQuery)
                .shortcutId(SearchManager.SUGGEST_NEVER_MAKE_SHORTCUT)
                .build();
    }

    /**
     * Creates a one-off suggestion for visiting the url specified by the current query,
     * or null if the current query does not look like a url.
     */
    public SuggestionData createGoToWebsiteSuggestion() {
        if (!Regex.WEB_URL_PATTERN.matcher(mQuery).matches()) {
            return null;
        }

        String url = mQuery.trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            url = "http://" + url;
        }
        return new SuggestionData.Builder(BUILTIN_SOURCE_COMPONENT)
                .format("html")
                .title(mContext.getString(R.string.go_to_website_title))
                .description(applySearchUrlColor(mQuery))
                .icon1(R.drawable.globe)
                .intentAction(Intent.ACTION_VIEW)
                .intentData(url)
                .build();
    }

    /**
     * Wraps the provided url string in the appropriate html formatting to apply the
     * theme-based search url color.
     */
    private String applySearchUrlColor(String url) {
        if (mSearchUrlColorHex == null) {
            // Get the color used for this purpose from the current theme.
            TypedValue colorValue = new TypedValue();
            mContext.getTheme().resolveAttribute(
                    com.android.internal.R.attr.textColorSearchUrl, colorValue, true);
            int color = mContext.getResources().getColor(colorValue.resourceId);

            // Convert the int color value into a hex string, and strip the first two
            // characters which will be the alpha transparency (html doesn't want this).
            mSearchUrlColorHex = Integer.toHexString(color).substring(2);
        }

        return "<font color=\"#" + mSearchUrlColorHex + "\">" + url + "</font>";
    }

}
