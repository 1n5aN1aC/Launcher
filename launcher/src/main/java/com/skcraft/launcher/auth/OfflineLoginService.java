/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.auth;

import java.io.IOException;

/**
 * Login service for offline accounts that generates UUIDs from usernames.
 */
public class OfflineLoginService implements LoginService {

    /**
     * Create a new offline session with the given username.
     *
     * @param username the player name
     * @return a new offline session
     */
    public Session login(String username) {
        return new OfflineSession(username);
    }

    @Override
    public Session restore(SavedSession savedSession)
            throws IOException, InterruptedException, AuthenticationException {
        
        if (savedSession.getType() != UserType.OFFLINE) {
            throw new AuthenticationException("Cannot restore non-offline session with OfflineLoginService");
        }
        
        // For offline sessions, we can always "restore" by creating a new session with the saved username
        return new OfflineSession(savedSession.getUsername());
    }
}