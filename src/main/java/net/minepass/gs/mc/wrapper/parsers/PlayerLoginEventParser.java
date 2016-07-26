/*
 *  This file is part of MinePass, licensed under the MIT License (MIT).
 *
 *  Copyright (c) MinePass.net <http://www.minepass.net>
 *
 *  Permission is hereby granted, free of charge, to any person obtaining a copy
 *  of this software and associated documentation files (the "Software"), to deal
 *  in the Software without restriction, including without limitation the rights
 *  to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 *  copies of the Software, and to permit persons to whom the Software is
 *  furnished to do so, subject to the following conditions:
 *
 *  The above copyright notice and this permission notice shall be included in
 *  all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package net.minepass.gs.mc.wrapper.parsers;

import net.minepass.api.gameserver.MPPlayer;
import net.minepass.gs.mc.wrapper.EventParser;
import net.minepass.gs.mc.wrapper.MP_MinecraftWrapper;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PlayerLoginEventParser extends EventParser {

    public PlayerLoginEventParser(MP_MinecraftWrapper wrapper) {
        super(wrapper);
    }

    @Override
    protected String getPatternString(String minecraftVersion) {
        return "(?<name>.+) joined the game";
    }

    @Override
    protected Status run(Matcher m) {
        try {
            // In case we're going to kick the player, give time for the login to complete
            // so that we avoid a Broken Pipe message to the client.
            Thread.sleep(1500);
        } catch (InterruptedException e) {
            // Ignore.
        }

        String playerLoginName = m.group("name");
        MPPlayer player;

        // Pull UUID from authenticator and push to currentPlayers.
        UUID uuid = getState().playerAuthUUIDs.get(playerLoginName);
        if (uuid != null) {
            getState().currentPlayers.put(playerLoginName, uuid);
        } else {
            wrapper.getLogger().error("Authenticator did not store UUID of player ".concat(playerLoginName), this);
        }

        // Lookup player pass and set game mode.
        // NOTE: Other pass related events take place via ScheduledTasks.
        //
        if (uuid != null && (player = wrapper.getMinepass().getPlayer(uuid)) != null) {
            Integer minecraftGameMode = -1;
            Pattern privPattern = Pattern.compile("mc:(?<name>[a-z]+)");
            Pattern commandPattern = Pattern.compile("mc:/(?<command>.+)");

            Matcher pm;
            for (String p : player.privileges) {
                if ((pm = privPattern.matcher(p)).find()) {
                    // Standard privileges.
                    //
                    switch (pm.group("name")) {
                        case "survival":
                            minecraftGameMode = 0;
                            break;
                        case "creative":
                            minecraftGameMode = 1;
                            break;
                        case "adventure":
                            minecraftGameMode = 2;
                            break;
                        case "spectator":
                            minecraftGameMode = 3;
                            break;
                    }
                } else if ((pm = commandPattern.matcher(p)).find()) {
                    // Command privileges.
                    //
                    String command = pm.group("command");
                    command = command.replaceAll("\\$name", player.name);
                    command = command.replaceAll("\\$uuid", uuid.toString());
                    wrapper.getLogger().debug("Sending login command: ".concat(command), this);
                    wrapper.getConsoleManager().sendCommand(command);
                }
            }

            if (minecraftGameMode > -1) {
                wrapper.getServerManager().setPlayerGameMode(playerLoginName, minecraftGameMode);
            } else {
                wrapper.getServerManager().kickPlayer(playerLoginName, "Your current MinePass does not permit access to this server.");
            }
        }

        return Status.HANDLED;
    }

    @Override
    protected boolean isEnabled() {
        return getState().minepassStarted;
    }
}
