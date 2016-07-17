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

import net.minepass.gs.InputBridge;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

/**
 * The ConsoleManager creates an InputBridge between the vanilla Minecraft
 * server and the system's Standard-In (Console). Commands can be then
 * be sent to the server from the other MinePass threads.
 *
 * @see InputBridge
 */
public class ConsoleManager implements Runnable {

    private MP_MinecraftWrapper wrapper;
    private InputStream consoleInput;
    private InputBridge bridge;

    public ConsoleManager(MP_MinecraftWrapper wrapper) {
        this.wrapper = wrapper;
        consoleInput = System.in;
        bridge = new InputBridge();
        System.setIn(bridge);
    }

    @Override
    public void run() {
        BufferedReader br = new BufferedReader(new InputStreamReader(consoleInput));
        String input;

        try {
            while((input=br.readLine())!=null){
                sendCommand(input);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void sendCommand(String command) {
        bridge.write(command + "\n");
    }
}
