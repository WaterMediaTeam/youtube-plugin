package org.watermedia.api.platform.extension.ytdlp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.WaterMedia;
import org.watermedia.api.platform.extension.botguard.BotGuardException;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Runs the yt-dlp binary as a child process and returns the parsed {@code --dump-single-json} output.
 *
 * <p>Out-of-process (like ffmpeg) keeps any failure inside yt-dlp from touching the JVM, and lets the
 * binary be swapped/updated independently. yt-dlp already resolves the signature and throttling (n)
 * parameters, so the {@code formats[].url} values are playable as-is (they only need the matching
 * {@code http_headers} sent at playback time).
 */
public final class YtDlpClient {
    private static final long TIMEOUT_SECONDS = 120; // SINGLE VIDEO ~SECONDS; PLAYLISTS CAN BE SLOWER

    private final YtDlpBinary binary;

    public YtDlpClient() {
        this.binary = new YtDlpBinary();
    }

    /**
     * Resolves {@code url} to yt-dlp's JSON info object.
     *
     * @param url       the media URL
     * @param extraArgs additional yt-dlp args (e.g. {@code --no-playlist}, {@code --extractor-args ...})
     * @return the parsed JSON (a single media object, or a playlist object with {@code entries[]})
     * @throws BotGuardException if the binary cannot be provisioned, times out, or exits non-zero
     */
    public JsonObject info(final String url, final List<String> extraArgs) throws BotGuardException {
        final var exe = this.binary.executable();

        final List<String> command = new ArrayList<>();
        command.add(exe.toString());
        command.add("-J");
        command.add("--no-warnings");
        command.add("--ignore-config");          // HERMETIC: IGNORE THE USER'S yt-dlp.conf
        command.add("--cache-dir");
        command.add(WaterMedia.tmp().resolve("yt-dlp").resolve("cache").toString());
        command.add("--socket-timeout");
        command.add("30");
        command.add("--retries");
        command.add("3");
        if (extraArgs != null) {
            command.addAll(extraArgs);
        }
        command.add(url);

        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (final Exception e) {
            throw new BotGuardException("Could not start yt-dlp", e);
        }

        // DRAIN STDERR ON A SEPARATE THREAD SO A FULL PIPE CANNOT DEADLOCK THE STDOUT READ
        final StringBuilder stderr = new StringBuilder();
        final Thread drain = new Thread(() -> stderr.append(readAll(process.getErrorStream())), "ytdlp-stderr");
        drain.setDaemon(true);
        drain.start();

        final String stdout = readAll(process.getInputStream());
        try {
            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BotGuardException("yt-dlp timed out after " + TIMEOUT_SECONDS + "s");
            }
            drain.join(1000);
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BotGuardException("Interrupted while waiting for yt-dlp", e);
        }

        final int exit = process.exitValue();
        if (exit != 0) {
            final String err = stderr.toString().trim();
            throw new BotGuardException("yt-dlp exited " + exit + ": "
                    + (err.isEmpty() ? "(no stderr)" : err.lines().reduce((a, b) -> b).orElse(err)));
        }
        try {
            return JsonParser.parseString(stdout).getAsJsonObject();
        } catch (final Exception e) {
            throw new BotGuardException("yt-dlp produced unparseable JSON", e);
        }
    }

    private static String readAll(final InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return "";
        }
    }
}
