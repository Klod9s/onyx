package com.onyx.perfmonitor;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

/**
 * Tiny root-side process: {@code su -c app_process ... RootMetricsHelper}.
 *
 * It reuses {@link ShizukuShellService}'s in-process sysfs reader, so a metrics
 * sample costs a handful of direct file reads instead of a shell script that
 * forks one process per value. Protocol (one request per line on stdin):
 *   PING                               -> "OK"
 *   M\t<gpuFreq>\t<gpuLoad>\t<mali>\t<0|1> -> same marker text as readMetrics()
 * Every reply is followed by a line holding {@link #END_MARKER}. The process
 * exits on EOF (the app closed the pipe or died) or on "exit".
 */
public final class RootMetricsHelper {
    static final String END_MARKER = "__ONYX_HELPER_END__";

    private RootMetricsHelper() {
    }

    public static void main(String[] args) {
        try {
            PrintStream out = new PrintStream(
                    new FileOutputStream(FileDescriptor.out), false, "UTF-8");
            BufferedReader in = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8));
            ShizukuShellService service = new ShizukuShellService();

            String line;
            while ((line = in.readLine()) != null) {
                if (line.equalsIgnoreCase("exit")) break;
                String reply = handle(service, line);
                out.print(reply == null ? "" : reply);
                out.print("\n" + END_MARKER + "\n");
                out.flush();
            }
        } catch (Throwable ignored) {
            // Falls through to exit; the app falls back to its shell reader.
        }
        System.exit(0);
    }

    private static String handle(ShizukuShellService service, String line) {
        try {
            if (line.equals("PING")) return "OK";
            if (line.startsWith("M\t")) {
                String[] p = line.split("\t", -1);
                if (p.length < 5) return "";
                return service.readMetrics(p[1], p[2], p[3], "1".equals(p[4]));
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
