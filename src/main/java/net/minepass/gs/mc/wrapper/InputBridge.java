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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * A thread-safe InputStream that accepts manual input.
 */
public class InputBridge extends InputStream {

    private ConcurrentLinkedQueue<Byte> buffer;

    public InputBridge() {
        super();
        buffer = new ConcurrentLinkedQueue<>();
    }

    @Override
    public int read() throws IOException {
        Byte b = buffer.poll();

        while (b == null) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return -1;
            }
            b = buffer.poll();
        }

        return b.intValue();
    }

    public synchronized void write(String string) {
        for (Byte b : string.getBytes(Charset.forName("UTF-8"))) {
            buffer.add(b);
        }
    }

    @Override
    public int available() throws IOException {
        return buffer.size();
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        /*
             In order to mimic the semi-blocked nature of console
             input, bulk reads are limited to either the current
             length of the storage buffer, or a single character.

             Since each call to read() blocks, attempting to read
             up to the entire max length of the request would
             stall until the console input exceeds that length.

             As we expect command-style input via readLine(),
             it's important to not block past having received
             the newline character.

             The non-desired behavior is to have the buffer
             create a situation where a command is stalled
             because the buffer is under filled.

             The desired behavior can be described as:

               - If the buffer is not empty, return as much
                 as possible up to "len", without blocking.

               - If the buffer is empty, block only until
                 the first character is available, because
                 the next read cycle can pick up the rest,
                 which will then not exceed/block beyond
                 the current length of the buffer. (#1)
         */
        if (len > 0) {
            len = Math.min(len, available());
            len = Math.max(len, 1);
        }
        return super.read(b, off, len);
    }

}
