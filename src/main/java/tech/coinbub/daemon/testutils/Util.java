package tech.coinbub.daemon.testutils;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class Util {
    private Util() {}

    public static Map<String, String> headers(final String username, final String password) {
        final String cred = Base64.getEncoder().encodeToString((username + ":" + password)
                .getBytes(StandardCharsets.UTF_8));
        final Map<String, String> headers = new HashMap<>();
        headers.put("Authorization", "Basic " + cred);
        headers.put("Content-Type", "application/json");
        return headers;
    }
}
