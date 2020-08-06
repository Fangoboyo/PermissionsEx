/*
 * PermissionsEx
 * Copyright (C) zml and PermissionsEx contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ca.stellardrift.permissionsex.sponge;

import ca.stellardrift.permissionsex.subject.SubjectTypeDefinition;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.profile.GameProfileCache;

import java.util.Optional;
import java.util.UUID;

/**
 * Metadata for user types
 */
public class UserSubjectTypeDescription extends SubjectTypeDefinition<Player> {
    private final PermissionsExPlugin plugin;

    public UserSubjectTypeDescription(String typeName, PermissionsExPlugin plugin) {
        super(typeName);
        this.plugin = plugin;
    }

    @Override
    public boolean isNameValid(String name) {
        try {
            UUID.fromString(name);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    @Override
    public Optional<String> getAliasForName(String input) {
        try {
            UUID.fromString(input);
        } catch (IllegalArgumentException ex) {
            Optional<Player> player = plugin.getGame().getServer().getPlayer(input);
            if (player.isPresent()) {
                return Optional.of(player.get().getUniqueId().toString());
            } else {
                GameProfileCache res = plugin.getGame().getServer().getGameProfileManager().getCache();
                for (GameProfile profile : res.match(input)) {
                    if (profile.getName().isPresent() && profile.getName().get().equalsIgnoreCase(input)) {
                        return Optional.of(profile.getUniqueId().toString());
                    }
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<Player> getAssociatedObject(String identifier) {
        try {
            return plugin.getGame().getServer().getPlayer(UUID.fromString(identifier));
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
