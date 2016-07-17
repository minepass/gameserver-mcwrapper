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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Event parsers are executed by the Server Manager in response
 * to logged output from the game server.
 *
 * Each parser has a primary pattern, as well as filters
 * for thread name and log level.
 *
 * Logged output that may span multiple lines can be handled by
 * returning the HOLD status from the primary pattern, after
 * which the Server Manager will continue to pass input from
 * the matching thread until told to stop.
 *
 * IMPORTANT: At this time any MinePass actions are performed
 * by the parsers themselves. This will likely eventually be
 * abstracted to generate actual events, both for clarity
 * and to potentially support other plugins.
 *
 * @see ServerManager
 * @see #run(Matcher)
 * @see #continuedInput(String)
 */
public abstract class EventParser {

    public enum Status {
        IGNORED, HANDLED, HOLD
    }

    protected MP_MinecraftWrapper wrapper;
    protected Pattern pattern;

    public EventParser(MP_MinecraftWrapper wrapper) {
        this.wrapper = wrapper;
        pattern = Pattern.compile(getPatternString(wrapper.getState().minecraftVersion));
    }

    public Status acceptLogMessage(String logMessage, boolean continued) {
        if (continued) {
            if (continuedInput(logMessage)) {
                return Status.HOLD;
            } else {
                return Status.HANDLED;
            }
        }

        Matcher m = pattern.matcher(logMessage);
        if (m.find()) {
            wrapper.getLogger().debug("Matched event parser.", this);
            return run(m);
        }
        return Status.IGNORED;
    }

    public boolean filterLevel(String levelName) {
        return true;
    }

    public boolean filterThread(String threadName) {
        return threadName.equalsIgnoreCase("server thread");
    }

    protected CommonState getState() {
        return wrapper.getState();
    }

    protected ServerManager getServerManager() {
        return wrapper.getServerManager();
    }

    protected abstract String getPatternString(String minecraftVersion);

    protected abstract Status run(Matcher m);

    /**
     * Executed (repeatedly) if #run returns Status.HOLD
     * Input will continue until this method returns
     * as non-true value.
     *
     * @param logInput
     * @return
     */
    protected boolean continuedInput(String logInput) {
        return false;
    }

    protected abstract boolean isEnabled();


    /**
     * Compares two version strings.
     *
     * Use this instead of String.compareTo() for a non-lexicographical
     * comparison that works for version strings. e.g. "1.10".compareTo("1.6").
     *
     * @note It does not work if "1.10" is supposed to be equal to "1.10.0".
     * @see <a href="http://stackoverflow.com/a/6702029">http://stackoverflow.com/a/6702029</a>
     *
     * @param str1 a string of ordinal numbers separated by decimal points.
     * @param str2 a string of ordinal numbers separated by decimal points.
     * @return The result is a negative integer if str1 is _numerically_ less than str2.
     *         The result is a positive integer if str1 is _numerically_ greater than str2.
     *         The result is zero if the strings are _numerically_ equal.
     */
    static public Integer versionCompare(String str1, String str2)
    {
        String[] vals1 = str1.split("\\.");
        String[] vals2 = str2.split("\\.");
        int i = 0;
        // set index to first non-equal ordinal or length of shortest version string
        while (i < vals1.length && i < vals2.length && vals1[i].equals(vals2[i]))
        {
            i++;
        }
        // compare first non-equal ordinal number
        if (i < vals1.length && i < vals2.length)
        {
            int diff = Integer.valueOf(vals1[i]).compareTo(Integer.valueOf(vals2[i]));
            return Integer.signum(diff);
        }
        // the strings are equal or one string is a substring of the other
        // e.g. "1.2.3" = "1.2.3" or "1.2.3" < "1.2.3.4"
        else
        {
            return Integer.signum(vals1.length - vals2.length);
        }
    }
}
