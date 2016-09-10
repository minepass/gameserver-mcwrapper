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

import net.minepass.api.gameserver.MPAsciiArt;
import net.minepass.api.gameserver.MPWorldServerDetails;
import net.minepass.api.gameserver.embed.solidtx.TxLog;
import net.minepass.api.gameserver.embed.solidtx.TxSync;
import net.minepass.gs.InputBridge;
import net.minepass.gs.mc.MinePassMC;
import net.minepass.gs.mc.wrapper.parsers.AuthenticatorEventParser;
import net.minepass.gs.mc.wrapper.parsers.PlayerLoginEventParser;
import net.minepass.gs.mc.wrapper.parsers.PlayerLogoutEventParser;
import net.minepass.gs.mc.wrapper.parsers.ServerStartEventParser;
import net.minepass.gs.mc.wrapper.parsers.ServerStopEventParser;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.LinkedList;

/**
 * The ServerManager hooks into the log4j system to read status entries from the
 * vanilla Minecraft server, which are then used to trigger MinePass events.
 * <p/>
 * MinePass data syncing and scheduled tasks are started as secondary threads
 * once the ServerManager verifies that startup is completed. They are later
 * stopped when the manager detects the server is shutting down.
 *
 * Server commands are buffered through the InputBridge and
 * ConsoleManager, so there are no thread concurrency issues
 * in executing actions here.
 *
 * @see InputBridge
 * @see ConsoleManager
 */
public class ServerManager implements Runnable {

    private MP_MinecraftWrapper wrapper;

    private TxLog logger;
    private Method getNextLogEventMethod;
    private Object[] getNextLogEventArgs;

    private Thread syncThread;
    private Thread scheduledTasks;

    private LinkedList<EventParser> eventParsers;
    private HashMap<String, EventParser> eventParserHold;

    public ServerManager(MP_MinecraftWrapper wrapper) {
        this.wrapper = wrapper;
        this.logger = wrapper.getLogger();

        // Use reflection to access the Mojang log adapter, since it is
        // not available as a compile-time dependency.
        try {
            Class logClass = Class.forName("com.mojang.util.QueueLogAppender");
            this.getNextLogEventMethod = logClass.getDeclaredMethod("getNextLogEvent", String.class);
            this.getNextLogEventArgs = new Object[]{"MinePass"};  // name of queue from modified log4j2.xml
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            throw new RuntimeException("Could not hook into server log queue.", e);
        }

        this.eventParserHold = new HashMap<>();
        this.eventParsers = new LinkedList<>();
        initEventParsers();
    }

    private void initEventParsers() {
        eventParsers.add(new ServerStartEventParser(wrapper));
        eventParsers.add(new ServerStopEventParser(wrapper));
        eventParsers.add(new AuthenticatorEventParser(wrapper));
        eventParsers.add(new PlayerLoginEventParser(wrapper));
        eventParsers.add(new PlayerLogoutEventParser(wrapper));
    }

    private final int THREAD = 0;
    private final int LEVEL = 1;
    private final int MESSAGE = 2;

    @Override
    public void run() {
        EventParser.Status status;
        String logOutput;
        String[] l;

        while (true) {
            logOutput = getNextLogEvent();
            if (logOutput == null) {
                try {
                    Thread.sleep(750);
                } catch (InterruptedException e) {
                    return;
                }
                continue;
            }

            l = logOutput.split("\\|");  // THREAD, LEVEL, MESSAGE

            if (eventParserHold.containsKey(l[THREAD])) {
                // This log entry should be applied to an existing event on a held thread.
                //
                status = eventParserHold.get(l[THREAD]).acceptLogMessage(l[MESSAGE], true);
                if (status == EventParser.Status.HANDLED) {
                    eventParserHold.remove(l[THREAD]);
                }
            } else {
                // Search for applicable parser.
                //
                runParsers:
                for (EventParser p : eventParsers) {
                    if (!p.isEnabled())
                        continue;

                    if (!p.filterLevel(l[LEVEL]))
                        continue;

                    if (!p.filterThread(l[THREAD]))
                        continue;

                    status = p.acceptLogMessage(l[MESSAGE], false);

                    switch (status) {
                        case HANDLED:
                            break runParsers;
                        case HOLD:
                            eventParserHold.put(l[THREAD], p);
                            break runParsers;
                    }
                }
            }
        }
    }

    public void startMinePass() {
        if (getState().minepassStarted) {
            return;
        }
        getState().minepassStarted = true;

        // Whitelist mode.
        if (wrapper.getMinepass().getEnforceWhitelist()) {
            logger.info("Requiring whitelist enabled", this);
            sendServerCommand("whitelist on");
        } else {
            logger.warn("|     .^.                                             .^.     |", this);
            logger.warn("|    / ! \\            WHITELIST DISABLED             / ! \\    |", this);
            logger.warn("|   '-----'                                         '-----'   |", this);
            logger.warn("MinePass option [enforce_whitelist]=false", this);
            logger.warn("This server will be OPEN to unregistered visitors.", this);
            logger.warn("MinePass can only manage privileges of registered players.", this);
            logger.warn("If you are trying to accommodate existing players,", this);
            logger.warn("  consider using the Import/Bypass feature of the web-portal.", this);
            sendServerCommand("whitelist off");
        }

        // Start sync thread.
        this.syncThread = new Thread(new TxSync(wrapper.getMinepass(), 10));
        syncThread.setDaemon(false);  // ensure any disk writing finishes
        syncThread.start();

        // Start scheduled tasks.
        this.scheduledTasks = new Thread(new ScheduledTasks(wrapper), "MinePass");
        scheduledTasks.setDaemon(true);
        scheduledTasks.start();

        // Output MinePass logo.
        for (String x : MPAsciiArt.getLogo("System Ready")) {
            logger.info(x, null);
        }

        // Send server details.
        MPWorldServerDetails details = new MPWorldServerDetails();
        details.plugin_type = "mc-vanilla-wrapper";
        details.plugin_version = wrapper.getWrapperVersion();
        details.game_realm = "mc";
        details.game_version = getState().minecraftVersion;
        details.game_version_raw = String.format(
                "Minecraft %s / Vanilla",
                getState().minecraftVersion
        );
        if (!wrapper.getMinepass().getServer().whitelist_imported) {
            details.importWhitelist(MinePassMC.whitelistBackupFile);
        }
        wrapper.getMinepass().sendObject(details, null);
    }

    public void stopMinePass() {
        if (syncThread != null) {
            syncThread.interrupt();
        }
        if (scheduledTasks != null) {
            scheduledTasks.interrupt();
        }
        getState().minepassStarted = false;
    }

    public void setPlayerGameMode(String name, Integer mode) {
        sendServerCommand("gamemode", mode.toString(), String.format("@a[name=%s,m=!%s]", name, mode.toString()));
    }

    public void tellPlayerRaw(String name, String rawMessage) {
        sendServerCommand("tellraw", name, rawMessage);
    }

    public void tellPlayer(String name, String message) {
        sendServerCommand("tell", name, message);
    }

    public void kickPlayer(String name, String message) {
        sendServerCommand("kick", name, message);
    }

    private String getNextLogEvent() {
        try {
            return (String) getNextLogEventMethod.invoke(null, getNextLogEventArgs);
        } catch (IllegalAccessException | InvocationTargetException e) {
            e.printStackTrace();
        }

        return null;
    }

    private void sendServerCommand(String command, String... params) {
        StringBuilder sb = new StringBuilder(command);
        for (String p : params) {
            sb.append(" ").append(p);
        }
        sendServerCommand(sb.toString());
    }

    private void sendServerCommand(String command) {
        wrapper.getConsoleManager().sendCommand(command);
    }

    private CommonState getState() {
        return wrapper.getState();
    }
}
