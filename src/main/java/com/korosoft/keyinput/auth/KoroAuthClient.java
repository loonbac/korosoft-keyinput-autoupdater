package com.korosoft.keyinput.auth;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

/**
 * HTTPS client for the KoroAuth plugin's public listener (see {@code HttpBootstrap.java} /
 * {@code PublicAuthHandlers.java} in {@code plugins-custom/KoroAuth}), which the mod hits from
 * the launcher's {@link com.korosoft.keyinput.LoginScreen}.
 *
 * <p>Exact wire contract, read from the plugin source (not guessed):
 * <ul>
 *   <li>{@code POST /register} and {@code POST /login}, body {@code {"username": "...", "password": "..."}}.</li>
 *   <li>{@code POST /refresh}, body {@code {"token": "..."}}.</li>
 *   <li>All three respond {@code 200 {"token": "..."}} on success.</li>
 *   <li>Failures respond {@code {"error": "<code>"}} with the status documented on {@link AuthOutcome.Kind}.</li>
 * </ul>
 *
 * <p>Runs every request on the JDK's async {@link HttpClient} (virtual-thread-friendly, non-blocking
 * from the caller's perspective) so {@link com.korosoft.keyinput.LoginScreen} never stalls the
 * render thread waiting on the network.
 *
 * <p>Password handling: the plaintext password only ever exists as a {@code char[]} (from the
 * password {@code TextFieldWidget}) up to {@link #buildCredentialsBody}, which encodes it
 * straight into the UTF-8 request body bytes without ever materializing a {@code String} copy,
 * then zeroes the {@code char[]} before returning. The request body bytes themselves are zeroed
 * once the HTTP call completes. The password is never logged, at any level, anywhere in this class.
 */
public final class KoroAuthClient {

    /**
     * Auth backend host. Its own subdomain, published through Cloudflare Tunnel from the proxy
     * host (a {@code cloudflared} ingress rule {@code auth.korosoft.site -> http://127.0.0.1:<port>}).
     * Cloudflare terminates TLS at its edge with a CA-trusted certificate, so the JDK's default
     * trust store validates it — no bundled/pinned certificate needed. Served on the standard HTTPS
     * port (443); the plugin's loopback port is internal to the tunnel and never appears here. This
     * is deliberately NOT the Minecraft server host ({@code MainMenuScreen.SERVER_ADDRESS}).
     */
    public static final String AUTH_HOST = "auth.korosoft.site";

    private static final String BASE_URL = "https://" + AUTH_HOST;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final Logger LOGGER = LoggerFactory.getLogger("keyinput/koroauth");
    private static final Gson GSON = new Gson();

    private static volatile HttpClient httpClient;

    private KoroAuthClient() {
    }

    public static CompletableFuture<AuthOutcome> login(String username, char[] password) {
        return post("/login", buildCredentialsBody(username, password));
    }

    public static CompletableFuture<AuthOutcome> register(String username, char[] password) {
        return post("/register", buildCredentialsBody(username, password));
    }

    public static CompletableFuture<AuthOutcome> refresh(String token) {
        JsonObject body = new JsonObject();
        body.addProperty("token", token);
        return post("/refresh", GSON.toJson(body).getBytes(StandardCharsets.UTF_8));
    }

    private static CompletableFuture<AuthOutcome> post(String path, byte[] jsonBody) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder()
                    .uri(URI.create(BASE_URL + path))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(jsonBody))
                    .build();
        } catch (RuntimeException e) {
            Arrays.fill(jsonBody, (byte) 0);
            LOGGER.error("KoroAuth: failed to build the {} request", path, e);
            return CompletableFuture.completedFuture(AuthOutcome.of(AuthOutcome.Kind.NETWORK_ERROR));
        }

        return client().sendAsync(request, HttpResponse.BodyHandlers.ofByteArray())
                .thenApply(response -> parseResponse(path, response))
                .exceptionally(ex -> {
                    LOGGER.error("KoroAuth: {} request failed", path, ex);
                    return AuthOutcome.of(AuthOutcome.Kind.NETWORK_ERROR);
                })
                .whenComplete((outcome, ex) -> Arrays.fill(jsonBody, (byte) 0));
    }

    private static AuthOutcome parseResponse(String path, HttpResponse<byte[]> response) {
        int status = response.statusCode();
        JsonObject body;
        try {
            body = GSON.fromJson(new String(response.body(), StandardCharsets.UTF_8), JsonObject.class);
        } catch (JsonSyntaxException e) {
            body = null;
        }

        if (status == 200) {
            String token = (body != null && body.has("token") && body.get("token").isJsonPrimitive())
                    ? body.get("token").getAsString()
                    : null;
            if (token == null) {
                LOGGER.error("KoroAuth: {} returned 200 with no token field", path);
                return AuthOutcome.of(AuthOutcome.Kind.NETWORK_ERROR);
            }
            return AuthOutcome.success(token);
        }

        String error = (body != null && body.has("error") && body.get("error").isJsonPrimitive())
                ? body.get("error").getAsString()
                : null;

        AuthOutcome.Kind kind = switch (status) {
            case 400 -> "invalid_username".equals(error) ? AuthOutcome.Kind.INVALID_USERNAME : AuthOutcome.Kind.BAD_REQUEST;
            case 401 -> AuthOutcome.Kind.INVALID_CREDENTIALS;
            case 403 -> AuthOutcome.Kind.NOT_WHITELISTED;
            case 405 -> AuthOutcome.Kind.METHOD_NOT_ALLOWED;
            case 409 -> AuthOutcome.Kind.ALREADY_REGISTERED;
            case 429 -> AuthOutcome.Kind.RATE_LIMITED;
            default -> AuthOutcome.Kind.SERVER_ERROR;
        };
        LOGGER.warn("KoroAuth: {} failed, status={} error={}", path, status, error);
        return AuthOutcome.of(kind);
    }

    /**
     * Encodes {@code {"username": "...", "password": "..."}} directly into UTF-8 bytes. The
     * password never becomes a {@code String} on the way there. Takes ownership of {@code password}
     * and zeroes it before returning (success or failure), per the project's password-hygiene rule.
     */
    private static byte[] buildCredentialsBody(String username, char[] password) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(160);
        try (Writer writer = new OutputStreamWriter(out, StandardCharsets.UTF_8)) {
            writer.write("{\"username\":");
            writer.write(GSON.toJson(username));
            writer.write(",\"password\":\"");
            writeJsonEscaped(writer, password);
            writer.write("\"}");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            Arrays.fill(password, '\0');
        }
        return out.toByteArray();
    }

    /** Writes {@code chars} as the content of a JSON string literal, without ever forming a String from it. */
    private static void writeJsonEscaped(Writer writer, char[] chars) throws IOException {
        for (char c : chars) {
            switch (c) {
                case '"' -> writer.write("\\\"");
                case '\\' -> writer.write("\\\\");
                case '\n' -> writer.write("\\n");
                case '\r' -> writer.write("\\r");
                case '\t' -> writer.write("\\t");
                default -> {
                    if (c < 0x20) {
                        writer.write("\\u");
                        writer.write(String.format("%04x", (int) c));
                    } else {
                        writer.write(c);
                    }
                }
            }
        }
    }

    private static HttpClient client() {
        HttpClient existing = httpClient;
        if (existing != null) {
            return existing;
        }
        synchronized (KoroAuthClient.class) {
            if (httpClient == null) {
                // No custom SSLContext: the auth backend presents a real Let's Encrypt
                // certificate, so the JDK's default trust store (the platform CA set) validates
                // it. A MITM would need a CA-signed cert for AUTH_HOST, which it cannot obtain.
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(REQUEST_TIMEOUT)
                        .build();
            }
            return httpClient;
        }
    }
}
