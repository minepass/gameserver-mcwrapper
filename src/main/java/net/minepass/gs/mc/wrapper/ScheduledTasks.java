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

package net.minepass.gs.mc.wrapper;

import net.minepass.gs.GameserverTasks;
import net.minepass.gs.InputBridge;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Wrapper implementation of MinePass GameserverTasks.
 * Ensuring passes remain valid, etc.
 *
 * Although runnable in a separate thread, any commands
 * will be buffered via the input bridge and therefore
 * are threadsafe.
 *
 * Data can be shared with other threads via the common
 * state, which also uses threadsafe data structures.
 *
 * @see GameserverTasks
 * @see InputBridge
 * @see CommonState
 */
public class ScheduledTasks extends GameserverTasks implements Runnable {

    private MP_MinecraftWrapper wrapper;

    public ScheduledTasks(MP_MinecraftWrapper wrapper) {
        super(wrapper.getMinepass());
        this.wrapper = wrapper;
    }

    @Override
    protected Map<UUID, String> getCurrentPlayers() {
        HashMap<UUID, String> currentPlayers = new HashMap<>();
        for (Map.Entry<String, UUID> entry : wrapper.getState().currentPlayers.entrySet()) {
            currentPlayers.put(entry.getValue(), entry.getKey());
        }
        return currentPlayers;
    }

    @Override
    protected void updateAndReloadLocalAuth() {
        wrapper.getMinepass().updateLocalWhitelist();
        wrapper.getConsoleManager().sendCommand("whitelist reload");
        wrapper.getLogger().info("Whitelist updated", this);
    }

    @Override
    protected void kickPlayer(UUID playerId, String message) {
        wrapper.getServerManager().kickPlayer(wrapper.getState().playerAuthNames.get(playerId), message);
    }

    @Override
    protected void warnPlayer(UUID playerId, String message) {
        wrapper.getServerManager().tellPlayerRaw(playerId.toString(),
                String.format(
                        "[\"\",{\"text\":\"%s\",\"color\":\"gold\"}]",
                        message
                )
        );
    }

    @Override
    protected void warnPlayerPass(UUID playerId, String message) {
        wrapper.getServerManager().tellPlayerRaw(playerId.toString(),
                String.format(
                        "[\"\",{\"text\":\"%s\",\"color\":\"aqua\",\"clickEvent\":{\"action\":\"open_url\",\"value\":\"%s\"}}]",
                        message.concat(" Click to get your MinePass."),
                        wrapper.getMinepass().getServer().join_url
                )
        );
    }

    public void run() {
        while (true) {
            try {
                Thread.sleep(2000);  // 2 seconds
            } catch (InterruptedException e) {
                return;
            }

            runTasks();
        }
    }
}
