/*
 * Adapted from Leavesclip v3.0.10 (da1712d92bc2ad7976b5f426a1f3225a675a74b4).
 * Copyright (c) 2019 Kyle Wood (DemonWav)
 * Copyright (c) 2023 LeavesMC
 * SPDX-License-Identifier: MIT
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.leavesmc.leavesclip;

import java.lang.reflect.InvocationTargetException;

public final class Main {
    private static final int REQUIRED_JAVA = 25;

    public static void main(final String[] args) {
        // Keep this class loadable on old JVMs. Do not resolve Leavesclip until after the check.
        final String version = System.getProperty("java.specification.version", "unknown");
        if (javaFeature(version) < REQUIRED_JAVA) {
            System.err.println("SnowBall requires Java " + REQUIRED_JAVA + " or newer (running Java " + version + ").");
            System.exit(1);
            return;
        }

        try {
            Class.forName("org.leavesmc.leavesclip.Leavesclip")
                .getMethod("main", String[].class).invoke(null, (Object) args);
        } catch (final InvocationTargetException ex) {
            fail(ex.getCause());
        } catch (final Throwable ex) {
            fail(ex);
        }
    }

    private static int javaFeature(final String version) {
        final String modern = version.startsWith("1.") ? version.substring(2) : version;
        final int dot = modern.indexOf('.');
        try {
            return Integer.parseInt(dot < 0 ? modern : modern.substring(0, dot));
        } catch (final NumberFormatException ex) {
            return -1;
        }
    }

    private static void fail(final Throwable failure) {
        failure.printStackTrace(System.err);
        System.exit(1);
    }
}
