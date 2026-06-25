package org.watermedia.api.platform.extension.botguard;

import org.watermedia.api.util.NetRequest;
import org.watermedia.tools.IOTool;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Shared helpers for provisioning platform-specific native executables bundled by this extension
 * (rustypipe-botguard and yt-dlp): OS/arch detection, hash-verified download into the WaterMedia temp
 * dir, and POSIX executable bit. Keeps the download/verify logic in one place so each binary wrapper
 * only describes its own asset table or manifest.
 */
public final class NativeBinaries {
    private NativeBinaries() {}

    // NORMALISED OS TOKEN ("windows"/"macos"/"linux"), OR null IF UNSUPPORTED
    public static String os() {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("win")) return "windows";
        if (os.contains("mac") || os.contains("darwin")) return "macos";
        if (os.contains("nux") || os.contains("nix") || os.contains("aix")) return "linux";
        return null;
    }

    // NORMALISED ARCH TOKEN ("x86_64"/"aarch64"), OR null IF UNSUPPORTED
    public static String arch() {
        final String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        if (arch.equals("amd64") || arch.equals("x86_64") || arch.equals("x64")) return "x86_64";
        if (arch.equals("aarch64") || arch.equals("arm64")) return "aarch64";
        return null;
    }

    // DOWNLOADS url INTO dest VIA WATERMEDIA'S HTTP CLIENT (FOLLOWS REDIRECTS)
    public static void download(final String url, final Path dest) throws Exception {
        try (final NetRequest request = NetRequest.create(url).send()) {
            if (request.statusCode() != 200) {
                throw new BotGuardException("Download failed (HTTP " + request.statusCode() + "): " + url);
            }
            try (final InputStream in = request.inputStream()) {
                IOTool.write(in, dest.toFile());
            }
        }
    }

    // THROWS IF THE FILE'S SHA-256 DOES NOT MATCH expected (CASE-INSENSITIVE HEX)
    public static void verify(final Path file, final String expected) throws Exception {
        final String actual = sha256(file);
        if (!expected.equalsIgnoreCase(actual)) {
            Files.deleteIfExists(file);
            throw new BotGuardException("SHA-256 mismatch for " + file.getFileName()
                    + " (expected " + expected + ", got " + actual + ")");
        }
    }

    public static String sha256(final Path file) throws Exception {
        final MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (final InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            final byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        final byte[] hash = digest.digest();
        final StringBuilder sb = new StringBuilder(hash.length * 2);
        for (final byte b : hash) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    public static void makeExecutable(final Path file) {
        file.toFile().setExecutable(true);
    }

    // READS A WHOLE STREAM AS A UTF-8 STRING (SMALL TEXT RESPONSES)
    public static byte[] readAll(final InputStream in) throws IOException {
        return in.readAllBytes();
    }
}
