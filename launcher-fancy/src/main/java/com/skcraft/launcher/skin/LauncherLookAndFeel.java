/*
 * SKCraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.skin;

import com.formdev.flatlaf.FlatDarkLaf;

/**
 * A dark FlatLaf theme, tuned to approximate the old Graphite Substance skin.
 * UI default overrides live in the sibling {@code LauncherLookAndFeel.properties}
 * file, which FlatLaf loads automatically based on this class's name.
 */
public class LauncherLookAndFeel extends FlatDarkLaf {

    public static final String NAME = "LauncherLookAndFeel";

    @Override
    public String getName() {
        return NAME;
    }
}
