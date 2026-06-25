package org.watermedia.api.platform.extension.ytdlp;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.watermedia.api.util.NetRequest;

import java.io.IOException;

/**
 * Fetches a fresh {@code visitorData} from YouTube's InnerTube {@code visitor_id} endpoint. A BotGuard
 * session po_token must be bound to the same {@code visitorData} that is then handed to yt-dlp, so this
 * helper produces the value to feed both (replacing NewPipe's former visitor-data call).
 */
public final class YoutubeVisitorData {
    private static final String ENDPOINT = "https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false";
    private static final String CLIENT_VERSION = "2.20250601.01.00"; // WEB client; the endpoint is lenient
    private static final String BODY =
            "{\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"" + CLIENT_VERSION
            + "\",\"hl\":\"en\",\"gl\":\"US\"}}}";

    private YoutubeVisitorData() {}

    /** Returns a fresh {@code visitorData} string, or throws if the endpoint is unreachable/changed. */
    public static String fetch() throws Exception {
        try (final NetRequest request = NetRequest.create(ENDPOINT)
                .method("POST")
                .contentType("application/json")
                .header("X-YouTube-Client-Name", "1")
                .header("X-YouTube-Client-Version", CLIENT_VERSION)
                .body(BODY)
                .send()) {
            if (request.statusCode() != 200) {
                throw new IOException("visitor_id endpoint returned HTTP " + request.statusCode());
            }
            final JsonObject json = JsonParser.parseString(request.readAllAsString()).getAsJsonObject();
            return json.getAsJsonObject("responseContext").get("visitorData").getAsString();
        }
    }
}
