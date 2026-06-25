package org.watermedia.api.platform.extension.botguard;

import org.watermedia.WaterMedia;
import org.watermedia.tools.IOTool;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.watermedia.api.platform.extension.bootstrap.WaterMediaExtension.LOGGER;

/**
 * Resolves the platform-specific {@code rustypipe-botguard} executable, downloading it on demand from
 * the upstream Codeberg release and caching it under {@link WaterMedia#tmp()}. Shared download/verify
 * plumbing lives in {@link NativeBinaries}; this class owns the asset table and the {@code .tar.xz}/
 * {@code .zip} extraction (the rustypipe-botguard releases are archived, unlike yt-dlp's raw binaries).
 *
 * <p>A {@code -Dwatermedia.botguard.binary=<path>} system property overrides resolution for offline use.
 * Resolution is lazy (first {@link #executable()} call), so the download only occurs when a YouTube link
 * actually needs a po_token.
 */
final class BotGuardBinary {
    // PINNED UPSTREAM RELEASE: codeberg.org/ThetaDev/rustypipe-botguard (MIT)
    static final String VERSION = "0.1.2";
    private static final String BASE_URL = "https://codeberg.org/ThetaDev/rustypipe-botguard/releases/download/v" + VERSION + "/";
    private static final String OVERRIDE_PROPERTY = "watermedia.botguard.binary";
    private static final String SNAPSHOT_FILE = "bg_snapshot.bin";

    private final String asset;
    private final String sha256;
    private final boolean zip;
    private final String binaryName;
    private final String unsupportedReason;

    private volatile Path ready;

    BotGuardBinary() {
        final String os = NativeBinaries.os();
        final String arch = NativeBinaries.arch();
        String asset = null, sha256 = null, binaryName = "rustypipe-botguard", reason = null;
        boolean zip = false;

        // SHA-256 VERIFIED AGAINST THE v0.1.2 CODEBERG RELEASE
        switch (os == null || arch == null ? "?" : os + "-" + arch) {
            case "windows-x86_64" -> {
                asset = "rustypipe-botguard-v" + VERSION + "-x86_64-pc-windows-msvc.zip";
                sha256 = "edb9e482ca77e3e4ecd4a2d3270f58c9169f03770db1568b3429cc8cf4c4b279";
                zip = true;
                binaryName = "rustypipe-botguard.exe";
            }
            case "macos-aarch64" -> {
                asset = "rustypipe-botguard-v" + VERSION + "-aarch64-apple-darwin.tar.xz";
                sha256 = "472905be9612edec129690d62d4db0321d1f30a548e59f68f89b7374fad9606e";
            }
            case "macos-x86_64" -> {
                asset = "rustypipe-botguard-v" + VERSION + "-x86_64-apple-darwin.tar.xz";
                sha256 = "8cfb319de5498dee5fb9ce5c88587e7bacab59e0550a38c9ad52dae840249cef";
            }
            case "linux-aarch64" -> {
                asset = "rustypipe-botguard-v" + VERSION + "-aarch64-unknown-linux-gnu.tar.xz";
                sha256 = "4d038857374a69aea9be8ded981d93a776dc88d4e254f5c6d292746099abf69a";
            }
            case "linux-x86_64" -> {
                asset = "rustypipe-botguard-v" + VERSION + "-x86_64-unknown-linux-gnu.tar.xz";
                sha256 = "4f2ec561e8f9fadece7deadc6ce0624fbdedd852222c3eb194c22153b1323129";
            }
            default -> reason = "No rustypipe-botguard build for "
                    + System.getProperty("os.name") + "/" + System.getProperty("os.arch");
        }

        this.asset = asset;
        this.sha256 = sha256;
        this.zip = zip;
        this.binaryName = binaryName;
        this.unsupportedReason = reason;
    }

    // RESOLVED LAZILY (NOT IN THE CONSTRUCTOR) BECAUSE WaterMedia.tmp() IS ONLY VALID ONCE WATERMEDIA HAS
    // STARTED; THE CLIENT MAY BE CONSTRUCTED EARLIER DURING BOOTSTRAP. VERSION-SCOPED SO UPGRADES RE-FETCH.
    private Path versionDir() {
        return WaterMedia.tmp().resolve("botguard").resolve(VERSION);
    }

    // PATH OF THE BOTGUARD VM SNAPSHOT CACHE (SPEEDS UP REPEAT INVOCATIONS); VERSION-SCOPED SINCE THE
    // SNAPSHOT FORMAT IS TIED TO THE BINARY THAT WROTE IT
    Path snapshot() {
        return this.versionDir().resolve(SNAPSHOT_FILE);
    }

    // RESOLVES THE EXECUTABLE, DOWNLOADING AND EXTRACTING IT ONCE. THREAD-SAFE: CONCURRENT CALLERS
    // BLOCK ON THE SAME RESOLUTION INSTEAD OF RACING TO DOWNLOAD.
    Path executable() throws BotGuardException {
        Path p = this.ready;
        if (p != null) return p;
        synchronized (this) {
            if (this.ready != null) return this.ready;

            final String override = System.getProperty(OVERRIDE_PROPERTY);
            if (override != null && !override.isBlank()) {
                final Path o = Path.of(override);
                if (!Files.isRegularFile(o)) {
                    throw new BotGuardException("watermedia.botguard.binary points to a missing file: " + o);
                }
                NativeBinaries.makeExecutable(o);
                LOGGER.info("Using overridden rustypipe-botguard binary: {}", o);
                return this.ready = o;
            }

            if (this.unsupportedReason != null) {
                throw new BotGuardException(this.unsupportedReason);
            }

            final Path binary = this.versionDir().resolve(this.binaryName);
            try {
                if (!Files.isRegularFile(binary)) {
                    this.install(binary);
                }
                NativeBinaries.makeExecutable(binary);
            } catch (final BotGuardException e) {
                throw e;
            } catch (final Exception e) {
                throw new BotGuardException("Failed to provision rustypipe-botguard", e);
            }
            LOGGER.info("rustypipe-botguard {} ready at {}", VERSION, binary);
            return this.ready = binary;
        }
    }

    // DOWNLOADS THE RELEASE ARCHIVE, VERIFIES ITS HASH AND EXTRACTS THE SINGLE BINARY IT CONTAINS
    private void install(final Path binary) throws Exception {
        final Path versionDir = binary.getParent();
        Files.createDirectories(versionDir);
        final Path archive = versionDir.resolve(this.asset);

        LOGGER.info("Downloading rustypipe-botguard from {}", BASE_URL + this.asset);
        NativeBinaries.download(BASE_URL + this.asset, archive);
        NativeBinaries.verify(archive, this.sha256);

        if (this.zip) {
            try (InputStream in = Files.newInputStream(archive)) {
                IOTool.jarExtractZip(in, versionDir.toFile());
            }
        } else {
            extractTarXz(archive, binary);
        }
        Files.deleteIfExists(archive);

        if (!Files.isRegularFile(binary)) {
            throw new BotGuardException("Extraction did not produce the expected binary: " + binary);
        }
    }

    // EXTRACTS THE FIRST REGULAR FILE FROM A SINGLE-ENTRY .tar.xz (THE RUSTYPIPE-BOTGUARD ARCHIVES SHIP
    // EXACTLY ONE FILE). A FULL TAR LIBRARY WOULD BE OVERKILL HERE, SO WE PARSE THE 512-BYTE USTAR HEADER
    // DIRECTLY: BYTES [124,136) HOLD THE OCTAL FILE SIZE AND BYTE 156 THE TYPEFLAG.
    private static void extractTarXz(final Path archive, final Path destination) throws Exception {
        try (InputStream fin = Files.newInputStream(archive);
             XZInputStream xz = new XZInputStream(new BufferedInputStream(fin))) {
            final byte[] header = new byte[512];
            while (readFully(xz, header) == 512) {
                if (isAllZero(header)) break; // TWO ZERO BLOCKS MARK THE END OF THE ARCHIVE
                final long size = parseOctal(header, 124, 12);
                final char type = (char) (header[156] & 0xFF);
                if ((type == '0' || type == '\0') && size > 0) {
                    try (OutputStream out = Files.newOutputStream(destination)) {
                        copyExactly(xz, out, size);
                    }
                    return; // FOUND OUR BINARY
                }
                skipExactly(xz, size + padding(size)); // SKIP NON-FILE ENTRY AND ITS 512-BYTE PADDING
            }
            throw new BotGuardException("No regular file inside " + archive.getFileName());
        }
    }

    private static long parseOctal(final byte[] header, final int offset, final int length) {
        long value = 0;
        for (int i = offset; i < offset + length; i++) {
            final int c = header[i] & 0xFF;
            if (c == 0 || c == ' ') continue; // FIELD IS NUL/SPACE PADDED
            if (c < '0' || c > '7') break;
            value = (value << 3) + (c - '0');
        }
        return value;
    }

    private static long padding(final long size) {
        final long remainder = size % 512;
        return remainder == 0 ? 0 : 512 - remainder;
    }

    private static boolean isAllZero(final byte[] block) {
        for (final byte b : block) {
            if (b != 0) return false;
        }
        return true;
    }

    private static int readFully(final InputStream in, final byte[] buffer) throws IOException {
        int total = 0;
        int read;
        while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) != -1) {
            total += read;
        }
        return total;
    }

    private static void copyExactly(final InputStream in, final OutputStream out, final long count) throws IOException {
        final byte[] buffer = new byte[1 << 16];
        long remaining = count;
        while (remaining > 0) {
            final int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read == -1) throw new IOException("Truncated tar entry");
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skipExactly(final InputStream in, final long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            final long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() == -1) return; // EOF
                remaining--;
            } else {
                remaining -= skipped;
            }
        }
    }
}
