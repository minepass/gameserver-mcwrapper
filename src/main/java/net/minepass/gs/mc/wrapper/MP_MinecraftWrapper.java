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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Properties;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

import net.minepass.api.gameserver.MPAsciiArt;
import net.minepass.api.gameserver.MPConfigException;
import net.minepass.api.gameserver.MPStartupException;
import net.minepass.api.gameserver.embed.solidtx.TxStack;
import net.minepass.gs.mc.MinePassMC;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * MinePass wrapper for Vanilla Minecraft.
 * <p/>
 * This wrapper facilities the background syncing of MinePass authorization
 * data by creating secondary threads before launching the Minecraft server.
 * <p/>
 * The vanilla server JAR must be renamed/symlinked as minecraft_server.jar
 * and located in the same directory as the wrapper.
 * <p/>
 * In order to facilitate command functions a bridge is created between the
 * system's Standard-In (Console) and the Minecraft server's input.
 * <p/>
 * Standard console input is passed through as normal, however MinePass can
 * also send commands to the server as needed.
 * <p/>
 * In addition, the log4j system is reconfigured to copy server output to
 * the MinePass plugin so that it can respond to system and game events.
 * <p/>
 * Any command line parameters passed to the wrapper are forwarded to the
 * vanilla server JAR at startup.
 */
public class MP_MinecraftWrapper {

    static final String configFileName = "minepass.config";
    static final String serverJarFileName = "minecraft_server.jar";  // must match MANIFEST include

    // Main
    // ------------------------------------------------------------------------------------------------------------- //

    public static void main(String[] args) {
        ArrayList<String> serverArgs = new ArrayList<>();
        serverArgs.add("nogui");  // GUI mode seems to create logging intercept issues

        for (String a : args) {
            switch (a) {
                case "nogui":
                    // Ignore, already added.
                    break;
                default:
                    serverArgs.add(a);
            }
        }

        MP_MinecraftWrapper wrapper = new MP_MinecraftWrapper();

        if (wrapper.initMinePass()) {
            wrapper.launchManagers();

            // Delay to make the wrapper initial log output more visible, and give the managers time to initialize.
            try {
                Thread.sleep(1500);
            } catch (InterruptedException e) {
                return;
            }

            // Launch vanilla Minecraft.
            wrapper.launchServer(serverArgs.toArray(new String[serverArgs.size()]));
        }
    }

    // Wrapper Instance
    // ------------------------------------------------------------------------------------------------------------- //

    private Logger logger;
    private Boolean debug;
    private String wrapperVersion;
    private File serverJarFile;
    private MinePassMC minepass;
    private ConsoleManager consoleManager;
    private ServerManager serverManager;
    private final CommonState state = new CommonState();

    public MP_MinecraftWrapper() {
        this.wrapperVersion = properties.getProperty("version");

        // The vanilla server JAR must be properly named to conform to the wrapper's MANIFEST.
        serverJarFile = new File(serverJarFileName);
        if (!serverJarFile.exists()) {
            System.out.println("ERROR: Could not find " + serverJarFileName);
            System.out.println("Please download or rename the vanilla server binary.");
            throw new RuntimeException("Could not find " + serverJarFileName);
        }

        this.logger = LogManager.getLogger();
        logger.info(String.format("MinePass Wrapper (%s) for Minecraft", wrapperVersion));
    }

    /**
     * Ensure MinePass has a valid configuration and perform an initial sync if needed.
     * <p/>
     * An initial sync is only required following software updates, otherwise MinePass
     * data is transmitted asynchronously and the server is unaffected by any possible
     * network conditions.
     */
    private boolean initMinePass() {
        try {
            MinePassMC.whitelistFile.createNewFile();
        } catch (IOException e) {
            throw new RuntimeException("Could not create whitelist.", e);
        }

        try {
            debug = config.getProperty("debug_enabled", "false").equals("true");
            String api_host = config.getProperty("setup_api_host");
            String server_uuid = config.getProperty("setup_server_id");
            String server_secret = config.getProperty("setup_server_secret");

            if (debug) {
                TxStack.debug = true;
            }

            /**
             * The MinePass network stack is built upon SolidTX, an MIT licensed project
             * developed in collaboration with BinaryBabel OSS.
             *
             * The source code for the MinePass game server stack is available at:
             *   https://github.com/minepass/gameserver-core
             *
             * The source code and documentation for SolidTX is available at:
             *   https://github.com/org-binbab/solid-tx
             *
             */
            this.minepass = new MinePassMC("MCWrapper ".concat(wrapperVersion), api_host, server_uuid, server_secret);
            minepass.setContext(this);

            logger.info("MinePass Core Version: " + minepass.getVersion());
            logger.info("MinePass API Endpoint: " + api_host);
            logger.info("MinePass World Server UUID: " + minepass.getServerUUID());
        } catch (MPConfigException e) {
            for (String x : MPAsciiArt.getNotice("Configuration Update Required")) {
                logger.info(x);
            }
            logger.warn("Run the server configuration wizard at http://minepass.net");
            logger.warn("Then paste the configuration into minepass.config");
            return false;
        } catch (MPStartupException e) {
            e.printStackTrace();
            return false;
        }

        return true;
    }

    /**
     * Launch the primary wrapper managers in secondary threads.
     *
     * @see ConsoleManager
     * @see ServerManager
     */
    private void launchManagers() {
        if (minepass == null) {
            return;
        }

        this.consoleManager = new ConsoleManager(this);
        Thread consoleThread = new Thread(consoleManager, "MPConsole");
        consoleThread.setDaemon(true);
        consoleThread.start();

        this.serverManager = new ServerManager(this);
        Thread controlThread = new Thread(serverManager, "MinePass");
        controlThread.setDaemon(true);
        controlThread.start();
    }

    /**
     * Launch the vanilla Minecraft server with the provided args.
     *
     * @param args
     */
    private void launchServer(String[] args) {
        if (minepass == null) {
            return;
        }

        String serverMainClass = null;

        try (JarFile serverJarReader = new JarFile(serverJarFile)) {
            Manifest manifest = serverJarReader.getManifest();
            serverMainClass = manifest.getMainAttributes().getValue("Main-Class");
        } catch (IOException e) {
            throw new RuntimeException("Could not read manifest from " + serverJarFileName, e);
        }

        try {
            Class serverClass = Class.forName(serverMainClass);
            Method serverClassMain = serverClass.getDeclaredMethod("main", String[].class);
            Object[] serverArgs = {args};
            serverClassMain.invoke(null, serverArgs);
        } catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException | ClassNotFoundException e) {
            throw new RuntimeException("Failed to invoke server startup", e);
        }
    }

    public Logger getLogger() {
        return logger;
    }

    public MinePassMC getMinepass() {
        return minepass;
    }

    public ConsoleManager getConsoleManager() {
        return consoleManager;
    }

    public ServerManager getServerManager() {
        return serverManager;
    }

    public CommonState getState() {
        return state;
    }

    // Configuration
    // ------------------------------------------------------------------------------------------------------------- //

    public static final Properties config;

    static {
        File configFile = new File(configFileName);
        InputStream configFileInput;

        try {
            configFileInput = new FileInputStream(configFile);
        } catch (FileNotFoundException e) {
            // Use default config file from jar resource.
            configFileInput = MP_MinecraftWrapper.class.getResourceAsStream("/config.properties");
        }

        config = new Properties();
        try {
            config.load(configFileInput);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read properties file", e);
        } finally {
            closeQuietly(configFileInput);
        }

        // Save default config (if needed).
        if (!configFile.exists()) {
            try (OutputStream configFileOutput = new FileOutputStream(configFile)) {
                config.store(configFileOutput, "MinePass Configuration");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static final Properties properties;

    static {
        InputStream propertiesInput = MP_MinecraftWrapper.class.getResourceAsStream("/wrapper.properties");

        properties = new Properties();
        try {
            properties.load(propertiesInput);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read properties file", e);
        } finally {
            closeQuietly(propertiesInput);
        }
    }

    // IO Helpers
    // ------------------------------------------------------------------------------------------------------------- //

    /**
     * Close InputStream without extra try/catch.
     *
     * @param input the input to close
     */
    static public void closeQuietly(InputStream input) {
        try {
            if (input != null) {
                input.close();
            }
        } catch (IOException ioe) {
            // Ignore.
        }
    }

    /**
     * Close OutputStream without extra try/catch.
     *
     * @param output the output to close
     */
    static public void closeQuietly(OutputStream output) {
        try {
            if (output != null) {
                output.close();
            }
        } catch (IOException ioe) {
            // Ignore.
        }
    }
}
