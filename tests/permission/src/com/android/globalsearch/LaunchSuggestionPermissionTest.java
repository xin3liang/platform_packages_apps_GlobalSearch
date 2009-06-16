package com.android.globalsearch;

import android.test.AndroidTestCase;

/**
 * Tests com.android.globalsearch.LAUNCH_SUGGESTIONS protects launching {@link GlobalSearch}.
 */
public class LaunchSuggestionPermissionTest extends AndroidTestCase {

    public void testGlobalSearchRequires_LAUNCH_SUGGESTIONS() {
        assertActivityRequiresPermission(
                "com.android.globalsearch",
                "com.android.globalsearch.GlobalSearch",
                "com.android.globalsearch.LAUNCH_SUGGESTIONS");
    }
}
