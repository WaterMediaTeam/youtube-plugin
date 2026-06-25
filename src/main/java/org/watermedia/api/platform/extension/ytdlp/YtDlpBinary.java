package org.watermedia.api.platform.extension.ytdlp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.WaterMedia;
import org.watermedia.api.platform.extension.botguard.BotGuardException;
import org.watermedia.api.platform.extension.botguard.NativeBinaries;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.watermedia.api.platform.extension.bootstrap.WaterMediaExtension.LOGGER;

/**
 * Resolves the platform-specific yt-dlp <em>frozen</em> executable (bundles Python, no interpreter
 * needed) from the official yt-dlp releases, cached under {@link WaterMedia#tmp()}. The pinned version
 * and per-platform SHA-256 live in the bundled {@code /ytdlp/manifest.json}, which the CI bumps on each
 * new yt-dlp release. A {@code -Dwatermedia.ytdlp.binary=<path>} system property overrides resolution.
 *
 * <p>Resolution is lazy (on first {@link #executable()}), so the ~20&nbsp;MB download only happens when a
 * link actually needs extraction.
 */
public final class YtDlpBinary {
    private static final String MANIFEST = "/ytdlp/manifest.json";
    private static final String OVERRIDE_PROPERTY = "watermedia.ytdlp.binary";

    private final String version;
    private final String url;
    private final String sha256;
    private final String binaryName;
    private final String unsupportedReason;

    private volatile Path ready;

    public YtDlpBinary() {
        String version = null, url = null, sha256 = null, binaryName = null, reason = null;
        try {
            final JsonObject manifest;
            try (final var in = YtDlpBinary.class.getResourceAsStream(MANIFEST)) {
                if (in == null) throw new IllegalStateException("missing " + MANIFEST);
                manifest = JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
            }
            version = manifest.get("version").getAsString();
            final String base = manifest.get("baseUrl").getAsString();

            final String os = NativeBinaries.os();
            final String arch = NativeBinaries.arch();
            // MACOS SHIPS A SINGLE UNIVERSAL BINARY; OTHER OSES ARE PER-ARCH
            final String key = os == null ? null : (os.equals("macos") ? "macos" : os + "-" + arch);
            final JsonObject assets = manifest.getAsJsonObject("assets");
            if (key == null || arch == null || !assets.has(key)) {
                reason = "No yt-dlp build for " + os + "/" + arch;
            } else {
                final JsonObject asset = assets.getAsJsonObject(key);
                binaryName = asset.get("name").getAsString();
                sha256 = asset.get("sha256").getAsString();
                url = base + version + "/" + binaryName;
            }
        } catch (final Exception e) {
            reason = "Could not read yt-dlp manifest: " + e.getMessage();
        }
        this.version = version;
        this.url = url;
        this.sha256 = sha256;
        this.binaryName = binaryName;
        this.unsupportedReason = reason;
    }

    public String version() {
        return this.version;
    }

    // RESOLVES THE EXECUTABLE, DOWNLOADING+VERIFYING IT ONCE. THREAD-SAFE.
    public Path executable() throws BotGuardException {
        Path p = this.ready;
        if (p != null) return p;
        synchronized (this) {
            if (this.ready != null) return this.ready;

            final String override = System.getProperty(OVERRIDE_PROPERTY);
            if (override != null && !override.isBlank()) {
                final Path o = Path.of(override);
                if (!Files.isRegularFile(o)) {
                    throw new BotGuardException("watermedia.ytdlp.binary points to a missing file: " + o);
                }
                NativeBinaries.makeExecutable(o);
                LOGGER.info("Using overridden yt-dlp binary: {}", o);
                return this.ready = o;
            }

            if (this.unsupportedReason != null) {
                throw new BotGuardException(this.unsupportedReason);
            }

            // FROZEN yt-dlp ASSETS ARE THE EXECUTABLE ITSELF (NO ARCHIVE) — DOWNLOAD STRAIGHT TO THE BINARY
            final Path binary = WaterMedia.tmp().resolve("yt-dlp").resolve(this.version).resolve(this.binaryName);
            try {
                if (!Files.isRegularFile(binary)) {
                    Files.createDirectories(binary.getParent());
                    LOGGER.info("Downloading yt-dlp {} from {}", this.version, this.url);
                    NativeBinaries.download(this.url, binary);
                    NativeBinaries.verify(binary, this.sha256);
                }
                NativeBinaries.makeExecutable(binary);
            } catch (final BotGuardException e) {
                throw e;
            } catch (final Exception e) {
                throw new BotGuardException("Failed to provision yt-dlp", e);
            }
            LOGGER.info("yt-dlp {} ready at {}", this.version, binary);
            return this.ready = binary;
        }
    }
}
