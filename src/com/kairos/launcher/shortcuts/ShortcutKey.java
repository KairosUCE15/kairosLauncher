package com.kairos.launcher.shortcuts;

import android.content.ComponentName;
import android.content.Intent;
import android.os.UserHandle;

import com.kairos.launcher.ItemInfo;
import com.kairos.launcher.util.ComponentKey;

/**--------------------------------------------------------------
 * TEAM KAIROS :: DEV BA :: CLASS SYNOPSIS
 * Copyright (c) 2017 KAIROS
 *
 * A key that uniquely identifies a shortcut using its package, id, and user handle.
 ----------------------------------------------------------------*/
public class ShortcutKey extends ComponentKey {

    public ShortcutKey(String packageName, UserHandle user, String id) {
        // Use the id as the class name.
        super(new ComponentName(packageName, id), user);
    }

    public String getId() {
        return componentName.getClassName();
    }

    public static ShortcutKey fromInfo(ShortcutInfoCompat shortcutInfo) {
        return new ShortcutKey(shortcutInfo.getPackage(), shortcutInfo.getUserHandle(),
                shortcutInfo.getId());
    }

    public static ShortcutKey fromIntent(Intent intent, UserHandle user) {
        String shortcutId = intent.getStringExtra(
                ShortcutInfoCompat.EXTRA_SHORTCUT_ID);
        return new ShortcutKey(intent.getPackage(), user, shortcutId);
    }

    public static ShortcutKey fromItemInfo(ItemInfo info) {
        return fromIntent(info.getIntent(), info.user);
    }
}
