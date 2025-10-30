/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.auth;

/**
 * Represents the type of user for the account.
 */
public enum UserType {
    /**
     * Offline accounts with generated UUIDs.
     */
    OFFLINE("offline"),
    /**
     * Microsoft accounts login via OAuth.
     */
    MICROSOFT("msa");

    private final String id;

    UserType(String id) {
        this.id = id;
    }

    /**
     * Return the account type string as the game understands it
     *
     * @return the account type ID for passing to the game
     */
    public String getId() {
        return this.id;
    }
}
