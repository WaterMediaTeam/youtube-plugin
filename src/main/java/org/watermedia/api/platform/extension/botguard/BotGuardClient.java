package org.watermedia.api.platform.extension.botguard;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.watermedia.api.platform.extension.bootstrap.WaterMediaExtension.LOGGER;

/**
 * Generates YouTube po_tokens by driving the {@code rustypipe-botguard} CLI as a child process.
 *
 * <p>Running the tool out-of-process (rather than through JNI) keeps a crash in the bundled JavaScript
 * engine from taking down the host JVM: a failed mint is just a non-zero exit code, never a native fault
 * in Minecraft's address space.
 *
 * <p>Each identifier maps to a token: a video id yields the content/player token, a {@code visitorData}
 * string yields the session/streaming token. Tokens are cached until shortly before their reported
 * {@code valid_until}, so a session token minted once is reused across every video sharing that
 * {@code visitorData}. Process invocations are serialised to protect the shared snapshot cache file.
 */
public final class BotGuardClient {
    private static final long PROCESS_TIMEOUT_SECONDS = 60; // COLD START RUNS THE BOTGUARD VM + NETWORK
    private static final long EXPIRY_SKEW_SECONDS = 120;    // RE-MINT SLIGHTLY EARLY TO AVOID RACES
    private static final long FALLBACK_LIFETIME_SECONDS = 3600; // WHEN valid_until IS ABSENT

    private final BotGuardBinary binary;
    private final Map<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final Object runLock = new Object();

    public BotGuardClient() {
        this.binary = new BotGuardBinary();
    }

    // MINTS A SINGLE TOKEN FOR THE GIVEN IDENTIFIER (VIDEO ID OR VISITOR DATA)
    public String mint(final String identifier) throws BotGuardException {
        return this.mint(List.of(identifier)).get(identifier);
    }

    // MINTS TOKENS FOR EVERY IDENTIFIER, REUSING CACHED ONES AND SPAWNING A SINGLE PROCESS FOR THE REST
    public Map<String, String> mint(final Collection<String> identifiers) throws BotGuardException {
        final long now = System.currentTimeMillis() / 1000L;
        final Map<String, String> result = new HashMap<>();
        final List<String> missing = new ArrayList<>();

        for (final String id : identifiers) {
            final CachedToken cached = this.cache.get(id);
            if (cached != null && cached.expiry > now + EXPIRY_SKEW_SECONDS) {
                result.put(id, cached.value);
            } else {
                missing.add(id);
            }
        }
        if (missing.isEmpty()) {
            return result;
        }

        synchronized (this.runLock) {
            // ANOTHER THREAD MAY HAVE MINTED THESE WHILE WE WAITED FOR THE LOCK
            missing.removeIf(id -> {
                final CachedToken cached = this.cache.get(id);
                if (cached != null && cached.expiry > now + EXPIRY_SKEW_SECONDS) {
                    result.put(id, cached.value);
                    return true;
                }
                return false;
            });
            if (!missing.isEmpty()) {
                this.run(missing, result);
            }
        }
        return result;
    }

    // SPAWNS rustypipe-botguard FOR THE MISSING IDENTIFIERS AND PARSES ITS SINGLE OUTPUT LINE:
    // "<token1> <token2> ... <tokenN> valid_until=<unix> from_snapshot=<bool>" — THE FIRST N
    // WHITESPACE-SEPARATED FIELDS ARE THE TOKENS, IN THE SAME ORDER AS THE IDENTIFIERS WE PASSED.
    private void run(final List<String> identifiers, final Map<String, String> result) throws BotGuardException {
        final Path exe = this.binary.executable();

        final List<String> command = new ArrayList<>(identifiers.size() + 4);
        command.add(exe.toString());
        command.add("--snapshot-file");
        command.add(this.binary.snapshot().toString());
        command.add("--");
        command.addAll(identifiers);

        final Process process;
        try {
            process = new ProcessBuilder(command).start();
        } catch (final Exception e) {
            throw new BotGuardException("Could not start rustypipe-botguard", e);
        }

        // DRAIN STDERR ON A SEPARATE THREAD SO A FULL PIPE CAN NEVER DEADLOCK THE STDOUT READ
        final StringBuilder stderr = new StringBuilder();
        final Thread drain = new Thread(() -> stderr.append(readAll(process.getErrorStream())), "botguard-stderr");
        drain.setDaemon(true);
        drain.start();

        final String stdout = readAll(process.getInputStream());
        try {
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new BotGuardException("rustypipe-botguard timed out after " + PROCESS_TIMEOUT_SECONDS + "s");
            }
            drain.join(1000);
        } catch (final InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new BotGuardException("Interrupted while waiting for rustypipe-botguard", e);
        }

        final int exit = process.exitValue();
        if (exit != 0) {
            throw new BotGuardException("rustypipe-botguard exited " + exit + ": " + stderr.toString().trim());
        }
        this.parse(stdout, identifiers, result);
    }

    private void parse(final String stdout, final List<String> identifiers, final Map<String, String> result)
            throws BotGuardException {
        String line = null;
        for (final String candidate : stdout.split("\\R")) {
            if (!candidate.isBlank()) {
                line = candidate.trim();
                break;
            }
        }
        if (line == null) {
            throw new BotGuardException("rustypipe-botguard produced no output");
        }

        final String[] fields = line.split("\\s+");
        if (fields.length < identifiers.size()) {
            throw new BotGuardException("Expected " + identifiers.size() + " token(s) but got: " + line);
        }

        long expiry = System.currentTimeMillis() / 1000L + FALLBACK_LIFETIME_SECONDS;
        for (int i = identifiers.size(); i < fields.length; i++) {
            if (fields[i].startsWith("valid_until=")) {
                try {
                    expiry = Long.parseLong(fields[i].substring("valid_until=".length()));
                } catch (final NumberFormatException ignored) {
                    // KEEP THE FALLBACK LIFETIME
                }
            }
        }

        for (int i = 0; i < identifiers.size(); i++) {
            final String id = identifiers.get(i);
            final String token = fields[i];
            this.cache.put(id, new CachedToken(token, expiry));
            result.put(id, token);
        }
        LOGGER.debug("rustypipe-botguard minted {} token(s), valid until {}", identifiers.size(), expiry);
    }

    private static String readAll(final InputStream in) {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final Exception e) {
            return "";
        }
    }

    // A MINTED TOKEN AND ITS UNIX EXPIRY (SECONDS)
    private record CachedToken(String value, long expiry) {}
}
