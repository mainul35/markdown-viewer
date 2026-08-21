package com.mdviewer.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * What kind of machine this is - "HP ZBook Studio 15.6 inch G8 Mobile Workstation PC".
 *
 * <p>The machine list had a hostname and an operating system, and on an account with two
 * Windows laptops that is two rows reading the same thing. The model is what the reader
 * already recognises: it is what their own operating system shows them when they open its
 * settings, and it is the fact that makes one row the laptop and the other the desktop.
 *
 * <p>Read from where the system keeps it rather than derived from anything - the same
 * value Windows Settings shows, the same DMI field a Linux desktop reads, and the model
 * identifier macOS reports. Nothing here is a fingerprint of the hardware: it is the name
 * of a product line that millions of people own.
 *
 * <p>Looked up once and never on a path that anybody is waiting on. It runs a short-lived
 * process on two of the three platforms, and an application that stalled its own startup to
 * find out what it is running on would have chosen a poor thing to be slow about.
 */
public final class MachineModel {

    private static String cached;

    private MachineModel() {
    }

    /** The model, or an empty string when this system will not say. */
    public static synchronized String of() {
        if (cached == null) {
            cached = look();
        }
        return cached;
    }

    private static String look() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("win")) {
                return windows();
            }
            if (os.contains("mac")) {
                return clean(run("sysctl", "-n", "hw.model"));
            }
            return linux();
        } catch (Exception e) {
            /* Unknown is a perfectly good answer here. The label falls back to the hostname
               and the system, which is what it has always been. */
            return "";
        }
    }

    /**
     * Windows, from the BIOS keys the operating system fills in at boot.
     *
     * <p>The registry rather than WMI: {@code wmic} is deprecated and absent from recent
     * builds, and a PowerShell call costs a second of process startup for one string.
     */
    private static String windows() throws IOException, InterruptedException {
        String key = "HKLM\\HARDWARE\\DESCRIPTION\\System\\BIOS";
        String product = value(run("reg", "query", key, "/v", "SystemProductName"));
        String maker = value(run("reg", "query", key, "/v", "SystemManufacturer"));
        return withMaker(clean(product), clean(maker));
    }

    /** Linux, from DMI - plain files, so no process and nothing to time out. */
    private static String linux() {
        String product = read("/sys/devices/virtual/dmi/id/product_name");
        String maker = read("/sys/devices/virtual/dmi/id/sys_vendor");
        return withMaker(product, maker);
    }

    /**
     * The maker in front of the model, unless it is already there.
     *
     * <p>HP's product name begins with "HP", and Lenovo's does not begin with "Lenovo".
     * Prefixing unconditionally gives "HP HP ZBook Studio", which reads as a bug in the
     * one place the reader is looking for a name they recognise.
     */
    static String withMaker(String product, String maker) {
        if (product.isEmpty()) {
            return "";
        }
        if (maker.isEmpty() || product.toLowerCase(Locale.ROOT)
                .startsWith(maker.toLowerCase(Locale.ROOT))) {
            return product;
        }
        return maker + " " + product;
    }

    /**
     * The value out of {@code reg query} output, which is three columns of whitespace:
     *
     * <pre>    SystemProductName    REG_SZ    HP ZBook Studio 15.6 inch G8</pre>
     *
     * <p>Split on the type rather than on spaces, because the value has spaces in it and is
     * the only part worth having.
     */
    static String value(String output) {
        for (String line : output.split("\\R")) {
            int at = line.indexOf("REG_SZ");
            if (at >= 0) {
                return line.substring(at + "REG_SZ".length()).trim();
            }
        }
        return "";
    }

    /** Placeholders some manufacturers ship rather than leaving the field empty. */
    private static final java.util.Set<String> NOT_A_MODEL = java.util.Set.of(
            "system product name", "to be filled by o.e.m.", "default string",
            "not specified", "none", "invalid", "unknown", "system manufacturer");

    static String clean(String value) {
        String trimmed = value == null ? "" : value.trim();
        return NOT_A_MODEL.contains(trimmed.toLowerCase(Locale.ROOT)) ? "" : trimmed;
    }

    private static String read(String path) {
        try {
            return clean(Files.readString(Path.of(path), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return "";
        }
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        String output;
        try (var stream = process.getInputStream()) {
            output = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        /* Bounded, because this is asked on a machine somebody is waiting to use. A system
           that will not answer in two seconds is a system that does not know. */
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            return "";
        }
        return process.exitValue() == 0 ? output : "";
    }
}
