/*
 * SK's Minecraft Launcher
 * Copyright (C) 2010-2014 Albert Pham <http://www.sk89q.com> and contributors
 * Please see LICENSE.txt for license information.
 */

package com.skcraft.launcher.auth;

import lombok.Getter;
import lombok.NonNull;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

/**
 * An offline session.
 */
public class OfflineSession implements Session {

    private static Map<String, String> dummyProperties = Collections.emptyMap();

    @Getter
    private final String name;

    /**
     * Create a new offline session using the given player name.
     *
     * @param name the player name
     */
    public OfflineSession(@NonNull String name) {
        this.name = name;
    }

    @Override
    public String getUuid() {
        return generateUuidFromUsername(name).toString();
    }

    /**
     * Generate a UUID based on the username using MD5 hash.
     * This ensures the same username always gets the same UUID.
     */
    private UUID generateUuidFromUsername(String username) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(username.getBytes(StandardCharsets.UTF_8));
            
            // Use the hash bytes to create a UUID
            long msb = 0;
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                msb = (msb << 8) | (hash[i] & 0xff);
            }
            for (int i = 8; i < 16; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xff);
            }
            
            return new UUID(msb, lsb);
        } catch (NoSuchAlgorithmException e) {
            // Fallback to a deterministic UUID based on username hashcode
            return new UUID(0, username.hashCode());
        }
    }

    @Override
    public String getAccessToken() {
        return generateUuidFromUsername(name + "_access").toString();
    }

    @Override
    public Map<String, String> getUserProperties() {
        return dummyProperties;
    }

    @Override
    public String getSessionToken() {
        return String.format("token:%s:%s", getAccessToken(), getUuid());
    }

    @Override
    public UserType getUserType() {
        return UserType.OFFLINE;
    }

    @Override
    public byte[] getAvatarImage() {
        return null;
    }

    @Override
    public boolean isOnline() {
        return true; // Allow modpack updates even for offline accounts
    }

}
