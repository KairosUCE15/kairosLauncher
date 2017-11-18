/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.kairos.launcher.provider;

import android.content.ContentProviderOperation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Process;
import android.text.TextUtils;
import android.util.LongSparseArray;
import android.util.SparseBooleanArray;

import com.kairos.launcher.DefaultLayoutParser;
import com.kairos.launcher.LauncherAppState;
import com.kairos.launcher.LauncherAppWidgetInfo;
import com.kairos.launcher.LauncherFiles;
import com.kairos.launcher.LauncherSettings;
import com.kairos.launcher.Utilities;
import com.kairos.launcher.Workspace;
import com.kairos.launcher.compat.UserManagerCompat;
import com.kairos.launcher.config.FeatureFlags;
import com.kairos.launcher.config.ProviderConfig;
import com.kairos.launcher.logging.FileLog;
import com.kairos.launcher.model.GridSizeMigrationTask;
import com.kairos.launcher.util.LongArrayMap;
import com.kairos.launcher.AutoInstallsLayout;

import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**--------------------------------------------------------------
 * TEAM KAIROS :: DEV BA :: CLASS SYNOPSIS
 * Copyright (c) 2017 KAIROS
 *
 * Utility class to import data from another Launcher which is based on Launcher3 schema.
 ----------------------------------------------------------------*/
public class ImportDataTask {

    public static final String KEY_DATA_IMPORT_SRC_PKG = "data_import_src_pkg";
    public static final String KEY_DATA_IMPORT_SRC_AUTHORITY = "data_import_src_authority";

    private static final String TAG = "ImportDataTask";
    private static final int MIN_ITEM_COUNT_FOR_SUCCESSFUL_MIGRATION = 6;
    // Insert items progressively to avoid OOM exception when loading icons.
    private static final int BATCH_INSERT_SIZE = 15;

    private final Context mContext;

    private final Uri mOtherScreensUri;
    private final Uri mOtherFavoritesUri;

    private int mHotseatSize;
    private int mMaxGridSizeX;
    private int mMaxGridSizeY;

    private ImportDataTask(Context context, String sourceAuthority) {
        mContext = context;
        mOtherScreensUri = Uri.parse("content://" +
                sourceAuthority + "/" + LauncherSettings.WorkspaceScreens.TABLE_NAME);
        mOtherFavoritesUri = Uri.parse("content://" + sourceAuthority + "/" + LauncherSettings.Favorites.TABLE_NAME);
    }

    public boolean importWorkspace() throws Exception {
        ArrayList<Long> allScreens = LauncherDbUtils.getScreenIdsFromCursor(
                mContext.getContentResolver().query(mOtherScreensUri, null, null, null,
                        LauncherSettings.WorkspaceScreens.SCREEN_RANK));
        FileLog.d(TAG, "Importing DB from " + mOtherFavoritesUri);

        // During import we reset the screen IDs to 0-indexed values.
        if (allScreens.isEmpty()) {
            // No thing to migrate
            FileLog.e(TAG, "No data found to import");
            return false;
        }

        mHotseatSize = mMaxGridSizeX = mMaxGridSizeY = 0;

        // Build screen update
        ArrayList<ContentProviderOperation> screenOps = new ArrayList<>();
        int count = allScreens.size();
        LongSparseArray<Long> screenIdMap = new LongSparseArray<>(count);
        for (int i = 0; i < count; i++) {
            ContentValues v = new ContentValues();
            v.put(LauncherSettings.WorkspaceScreens._ID, i);
            v.put(LauncherSettings.WorkspaceScreens.SCREEN_RANK, i);
            screenIdMap.put(allScreens.get(i), (long) i);
            screenOps.add(ContentProviderOperation.newInsert(
                    LauncherSettings.WorkspaceScreens.CONTENT_URI).withValues(v).build());
        }
        mContext.getContentResolver().applyBatch(ProviderConfig.AUTHORITY, screenOps);
        importWorkspaceItems(allScreens.get(0), screenIdMap);

        GridSizeMigrationTask.markForMigration(mContext, mMaxGridSizeX, mMaxGridSizeY, mHotseatSize);

        // Create empty DB flag.
        LauncherSettings.Settings.call(mContext.getContentResolver(),
                LauncherSettings.Settings.METHOD_CLEAR_EMPTY_DB_FLAG);
        return true;
    }

    /**
     * 1) Imports all the workspace entries from the source provider.
     * 2) For home screen entries, maps the screen id based on {@param screenIdMap}
     * 3) In the end fills any holes in hotseat with items from default hotseat layout.
     */
    private void importWorkspaceItems(
            long firsetScreenId, LongSparseArray<Long> screenIdMap) throws Exception {
        String profileId = Long.toString(UserManagerCompat.getInstance(mContext)
                .getSerialNumberForUser(Process.myUserHandle()));

        boolean createEmptyRowOnFirstScreen = false;
        if (FeatureFlags.QSB_ON_FIRST_SCREEN) {
            try (Cursor c = mContext.getContentResolver().query(mOtherFavoritesUri, null,
                    // get items on the first row of the first screen
                    "profileId = ? AND container = -100 AND screen = ? AND cellY = 0",
                    new String[]{profileId, Long.toString(firsetScreenId)},
                    null)) {
                // First row of first screen is not empty
                createEmptyRowOnFirstScreen = c.moveToNext();
            }
        }

        ArrayList<ContentProviderOperation> insertOperations = new ArrayList<>(BATCH_INSERT_SIZE);

        // Set of package names present in hotseat
        final HashSet<String> hotseatTargetApps = new HashSet<>();
        int maxId = 0;

        // Number of imported items on workspace and hotseat
        int totalItemsOnWorkspace = 0;

        try (Cursor c = mContext.getContentResolver()
                .query(mOtherFavoritesUri, null,
                        // Only migrate the primary user
                        LauncherSettings.Favorites.PROFILE_ID + " = ?", new String[]{profileId},
                        // Get the items sorted by container, so that the folders are loaded
                        // before the corresponding items.
                        LauncherSettings.Favorites.CONTAINER)) {

            // various columns we expect to exist.
            final int idIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites._ID);
            final int intentIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.INTENT);
            final int titleIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.TITLE);
            final int containerIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CONTAINER);
            final int itemTypeIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ITEM_TYPE);
            final int widgetProviderIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.APPWIDGET_PROVIDER);
            final int screenIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SCREEN);
            final int cellXIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLX);
            final int cellYIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.CELLY);
            final int spanXIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANX);
            final int spanYIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.SPANY);
            final int rankIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.RANK);
            final int iconIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON);
            final int iconPackageIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON_PACKAGE);
            final int iconResourceIndex = c.getColumnIndexOrThrow(LauncherSettings.Favorites.ICON_RESOURCE);

            SparseBooleanArray mValidFolders = new SparseBooleanArray();
            ContentValues values = new ContentValues();

            while (c.moveToNext()) {
                values.clear();
                int id = c.getInt(idIndex);
                maxId = Math.max(maxId, id);
                int type = c.getInt(itemTypeIndex);
                int container = c.getInt(containerIndex);

                long screen = c.getLong(screenIndex);

                int cellX = c.getInt(cellXIndex);
                int cellY = c.getInt(cellYIndex);
                int spanX = c.getInt(spanXIndex);
                int spanY = c.getInt(spanYIndex);

                switch (container) {
                    case LauncherSettings.Favorites.CONTAINER_DESKTOP: {
                        Long newScreenId = screenIdMap.get(screen);
                        if (newScreenId == null) {
                            FileLog.d(TAG, String.format("Skipping item %d, type %d not on a valid screen %d", id, type, screen));
                            continue;
                        }
                        // Reset the screen to 0-index value
                        screen = newScreenId;
                        if (createEmptyRowOnFirstScreen && screen == Workspace.FIRST_SCREEN_ID) {
                            // Shift items by 1.
                            cellY++;
                        }

                        mMaxGridSizeX = Math.max(mMaxGridSizeX, cellX + spanX);
                        mMaxGridSizeY = Math.max(mMaxGridSizeY, cellY + spanY);
                        break;
                    }
                    case LauncherSettings.Favorites.CONTAINER_HOTSEAT: {
                        mHotseatSize = Math.max(mHotseatSize, (int) screen + 1);
                        break;
                    }
                    default:
                        if (!mValidFolders.get(container)) {
                            FileLog.d(TAG, String.format("Skipping item %d, type %d not in a valid folder %d", id, type, container));
                            continue;
                        }
                }

                Intent intent = null;
                switch (type) {
                    case LauncherSettings.Favorites.ITEM_TYPE_FOLDER: {
                        mValidFolders.put(id, true);
                        // Use a empty intent to indicate a folder.
                        intent = new Intent();
                        break;
                    }
                    case LauncherSettings.Favorites.ITEM_TYPE_APPWIDGET: {
                        values.put(LauncherSettings.Favorites.RESTORED,
                                LauncherAppWidgetInfo.FLAG_ID_NOT_VALID |
                                        LauncherAppWidgetInfo.FLAG_PROVIDER_NOT_READY |
                                        LauncherAppWidgetInfo.FLAG_UI_NOT_READY);
                        values.put(LauncherSettings.Favorites.APPWIDGET_PROVIDER, c.getString(widgetProviderIndex));
                        break;
                    }
                    case LauncherSettings.Favorites.ITEM_TYPE_SHORTCUT:
                    case LauncherSettings.Favorites.ITEM_TYPE_APPLICATION: {
                        intent = Intent.parseUri(c.getString(intentIndex), 0);
                        if (Utilities.isLauncherAppTarget(intent)) {
                            type = LauncherSettings.Favorites.ITEM_TYPE_APPLICATION;
                        } else {
                            values.put(LauncherSettings.Favorites.ICON_PACKAGE, c.getString(iconPackageIndex));
                            values.put(LauncherSettings.Favorites.ICON_RESOURCE, c.getString(iconResourceIndex));
                        }
                        values.put(LauncherSettings.Favorites.ICON,  c.getBlob(iconIndex));
                        values.put(LauncherSettings.Favorites.INTENT, intent.toUri(0));
                        values.put(LauncherSettings.Favorites.RANK, c.getInt(rankIndex));

                        values.put(LauncherSettings.Favorites.RESTORED, 1);
                        break;
                    }
                    default:
                        FileLog.d(TAG, String.format("Skipping item %d, not a valid type %d", id, type));
                        continue;
                }

                if (container == LauncherSettings.Favorites.CONTAINER_HOTSEAT) {
                    if (intent == null) {
                        FileLog.d(TAG, String.format("Skipping item %d, null intent on hotseat", id));
                        continue;
                    }
                    if (intent.getComponent() != null) {
                        intent.setPackage(intent.getComponent().getPackageName());
                    }
                    hotseatTargetApps.add(getPackage(intent));
                }

                values.put(LauncherSettings.Favorites._ID, id);
                values.put(LauncherSettings.Favorites.ITEM_TYPE, type);
                values.put(LauncherSettings.Favorites.CONTAINER, container);
                values.put(LauncherSettings.Favorites.SCREEN, screen);
                values.put(LauncherSettings.Favorites.CELLX, cellX);
                values.put(LauncherSettings.Favorites.CELLY, cellY);
                values.put(LauncherSettings.Favorites.SPANX, spanX);
                values.put(LauncherSettings.Favorites.SPANY, spanY);
                values.put(LauncherSettings.Favorites.TITLE, c.getString(titleIndex));
                insertOperations.add(ContentProviderOperation
                        .newInsert(LauncherSettings.Favorites.CONTENT_URI).withValues(values).build());
                if (container < 0) {
                    totalItemsOnWorkspace++;
                }

                if (insertOperations.size() >= BATCH_INSERT_SIZE) {
                    mContext.getContentResolver().applyBatch(ProviderConfig.AUTHORITY,
                            insertOperations);
                    insertOperations.clear();
                }
            }
        }
        FileLog.d(TAG, totalItemsOnWorkspace + " items imported from external source");
        if (totalItemsOnWorkspace < MIN_ITEM_COUNT_FOR_SUCCESSFUL_MIGRATION) {
            throw new Exception("Insufficient data");
        }
        if (!insertOperations.isEmpty()) {
            mContext.getContentResolver().applyBatch(ProviderConfig.AUTHORITY,
                    insertOperations);
            insertOperations.clear();
        }

        LongArrayMap<Object> hotseatItems = GridSizeMigrationTask.removeBrokenHotseatItems(mContext);
        int myHotseatCount = LauncherAppState.getIDP(mContext).numHotseatIcons;
        if (!FeatureFlags.NO_ALL_APPS_ICON) {
            myHotseatCount--;
        }
        if (hotseatItems.size() < myHotseatCount) {
            // Insufficient hotseat items. Add a few more.
            HotseatParserCallback parserCallback = new HotseatParserCallback(
                    hotseatTargetApps, hotseatItems, insertOperations, maxId + 1, myHotseatCount);
            new HotseatLayoutParser(mContext,
                    parserCallback).loadLayout(null, new ArrayList<Long>());
            mHotseatSize = (int) hotseatItems.keyAt(hotseatItems.size() - 1) + 1;

            if (!insertOperations.isEmpty()) {
                mContext.getContentResolver().applyBatch(ProviderConfig.AUTHORITY,
                        insertOperations);
            }
        }
    }

    private static final String getPackage(Intent intent) {
        return intent.getComponent() != null ? intent.getComponent().getPackageName()
                : intent.getPackage();
    }

    /**
     * Performs data import if possible.
     * @return true on successful data import, false if it was not available
     * @throws Exception if the import failed
     */
    public static boolean performImportIfPossible(Context context) throws Exception {
        SharedPreferences devicePrefs = getDevicePrefs(context);
        String sourcePackage = devicePrefs.getString(KEY_DATA_IMPORT_SRC_PKG, "");
        String sourceAuthority = devicePrefs.getString(KEY_DATA_IMPORT_SRC_AUTHORITY, "");

        if (TextUtils.isEmpty(sourcePackage) || TextUtils.isEmpty(sourceAuthority)) {
            return false;
        }

        // Synchronously clear the migration flags. This ensures that we do not try migration
        // again and thus prevents potential crash loops due to migration failure.
        devicePrefs.edit().remove(KEY_DATA_IMPORT_SRC_PKG).remove(KEY_DATA_IMPORT_SRC_AUTHORITY).commit();

        if (!LauncherSettings.Settings.call(context.getContentResolver(), LauncherSettings.Settings.METHOD_WAS_EMPTY_DB_CREATED)
                .getBoolean(LauncherSettings.Settings.EXTRA_VALUE, false)) {
            // Only migration if a new DB was created.
            return false;
        }

        for (ProviderInfo info : context.getPackageManager().queryContentProviders(
                null, context.getApplicationInfo().uid, 0)) {

            if (sourcePackage.equals(info.packageName)) {
                if ((info.applicationInfo.flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    // Only migrate if the source launcher is also on system image.
                    return false;
                }

                // Wait until we found a provider with matching authority.
                if (sourceAuthority.equals(info.authority)) {
                    if (TextUtils.isEmpty(info.readPermission) ||
                            context.checkPermission(info.readPermission, Process.myPid(),
                                    Process.myUid()) == PackageManager.PERMISSION_GRANTED) {
                        // All checks passed, run the import task.
                        return new ImportDataTask(context, sourceAuthority).importWorkspace();
                    }
                }
            }
        }
        return false;
    }

    private static SharedPreferences getDevicePrefs(Context c) {
        return c.getSharedPreferences(LauncherFiles.DEVICE_PREFERENCES_KEY, Context.MODE_PRIVATE);
    }

    private static final int getMyHotseatLayoutId(Context context) {
        return LauncherAppState.getIDP(context).numHotseatIcons <= 5
                ? com.kairos.launcher.R.xml.dw_phone_hotseat
                : com.kairos.launcher.R.xml.dw_tablet_hotseat;
    }

    /**
     * Extension of {@link DefaultLayoutParser} which only allows icons and shortcuts.
     */
    private static class HotseatLayoutParser extends DefaultLayoutParser {
        public HotseatLayoutParser(Context context, AutoInstallsLayout.LayoutParserCallback callback) {
            super(context, null, callback, context.getResources(), getMyHotseatLayoutId(context));
        }

        @Override
        protected HashMap<String, TagParser> getLayoutElementsMap() {
            // Only allow shortcut parsers
            HashMap<String, TagParser> parsers = new HashMap<String, TagParser>();
            parsers.put(TAG_FAVORITE, new AppShortcutWithUriParser());
            parsers.put(TAG_SHORTCUT, new UriShortcutParser(mSourceRes));
            parsers.put(TAG_RESOLVE, new ResolveParser());
            return parsers;
        }
    }

    /**
     * {@link AutoInstallsLayout.LayoutParserCallback} which adds items in empty hotseat spots.
     */
    private static class HotseatParserCallback implements AutoInstallsLayout.LayoutParserCallback {
        private final HashSet<String> mExisitingApps;
        private final LongArrayMap<Object> mExistingItems;
        private final ArrayList<ContentProviderOperation> mOutOps;
        private final int mRequiredSize;
        private int mStartItemId;

        HotseatParserCallback(
                HashSet<String> existingApps, LongArrayMap<Object> existingItems,
                ArrayList<ContentProviderOperation> outOps, int startItemId, int requiredSize) {
            mExisitingApps = existingApps;
            mExistingItems = existingItems;
            mOutOps = outOps;
            mRequiredSize = requiredSize;
            mStartItemId = startItemId;
        }

        @Override
        public long generateNewItemId() {
            return mStartItemId++;
        }

        @Override
        public long insertAndCheck(SQLiteDatabase db, ContentValues values) {
            if (mExistingItems.size() >= mRequiredSize) {
                // No need to add more items.
                return 0;
            }
            Intent intent;
            try {
                intent = Intent.parseUri(values.getAsString(LauncherSettings.Favorites.INTENT), 0);
            } catch (URISyntaxException e) {
                return 0;
            }
            String pkg = getPackage(intent);
            if (pkg == null || mExisitingApps.contains(pkg)) {
                // The item does not target an app or is already in hotseat.
                return 0;
            }
            mExisitingApps.add(pkg);

            // find next vacant spot.
            long screen = 0;
            while (mExistingItems.get(screen) != null) {
                screen++;
            }
            mExistingItems.put(screen, intent);
            values.put(LauncherSettings.Favorites.SCREEN, screen);
            mOutOps.add(ContentProviderOperation.newInsert(LauncherSettings.Favorites.CONTENT_URI).withValues(values).build());
            return 0;
        }
    }
}
