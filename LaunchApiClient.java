import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

public class LaunchApiClient {
    private static final String BASE_URL = "https://launch.micronaut.io";
    private static final Gson GSON = new Gson();
    
    private final HttpClient httpClient;
    private final Map<String, List<Feature>> featureCache = new HashMap<>();

    public LaunchApiClient() {
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public SelectOptions fetchSelectOptions() throws Exception {
        String url = BASE_URL + "/select-options";
        String body = fetch(url);

        return parseSelectOptions(body);
    }

    public CompletableFuture<SelectOptions> fetchSelectOptionsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchSelectOptions();
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch select options: " + e.getMessage(), e);
            }
        });
    }

    public List<Feature> fetchFeatures(String type, String lang, String build, 
                                        String test, String jdkVersion) throws Exception {
        String cacheKey = type + ":" + lang + ":" + build + ":" + test + ":" + jdkVersion;
        
        if (featureCache.containsKey(cacheKey)) {
            return featureCache.get(cacheKey);
        }

        String url = BASE_URL + "/application-types/" + encodePathSegment(type) + "/features?"
            + queryParam("lang", lang) + "&"
            + queryParam("build", build) + "&"
            + queryParam("test", test) + "&"
            + queryParam("javaVersion", jdkVersion);
        
        String body = fetch(url);
        List<Feature> features = parseFeatures(body);
        
        featureCache.put(cacheKey, features);
        return features;
    }

    public CompletableFuture<List<Feature>> fetchFeaturesAsync(String type, String lang, String build, 
                                                               String test, String jdkVersion) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return fetchFeatures(type, lang, build, test, jdkVersion);
            } catch (Exception e) {
                throw new RuntimeException("Failed to fetch features: " + e.getMessage(), e);
            }
        });
    }

    public InputStream generateProject(ProjectConfig config) throws Exception {
        String url = buildProjectEndpoint("create", config);

        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(5))
            .GET()
            .build();

        HttpResponse<InputStream> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofInputStream());

        if (response.statusCode() != 200) {
            throw new RuntimeException("Failed to generate project: HTTP " + response.statusCode());
        }

        return response.body();
    }

    public String fetchPreview(ProjectConfig config) throws Exception {
        String url = buildProjectEndpoint("preview", config);

        return fetch(url);
    }

    public String fetchDiff(ProjectConfig config) throws Exception {
        String url = buildProjectEndpoint("diff", config);

        return fetch(url);
    }

    public String generateCliCommand(ProjectConfig config) {
        StringBuilder cmd = new StringBuilder("mn create-app ");
        cmd.append(config.getName());
        
        if (!config.getBasePackage().equals("com.example")) {
            cmd.append(" --package ").append(config.getBasePackage());
        }
        cmd.append(" --lang ").append(config.getLang());
        cmd.append(" --build ").append(config.getBuild());
        cmd.append(" --test ").append(config.getTest());
        cmd.append(" --java-version ").append(config.getJdkVersion());
        
        if (!config.getFeatures().isEmpty()) {
            cmd.append(" --features ").append(String.join(",", config.getFeatures()));
        }
        
        return cmd.toString();
    }

    public String generateCurlCommand(ProjectConfig config) {
        String url = buildProjectEndpoint("create", config);

        return "curl -o " + config.getName() + ".zip " + url;
    }

    private String fetch(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("Accept", "application/json")
            .GET()
            .build();

        HttpResponse<String> response = httpClient.send(request, 
            HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new RuntimeException("HTTP request failed: " + response.statusCode() + " - " + response.body());
        }

        return response.body();
    }

    private List<Feature> parseFeatures(String json) {
        List<Feature> features = new ArrayList<>();
        
        try {
            JsonElement rootElement = GSON.fromJson(json, JsonElement.class);
            JsonArray featuresArray = extractFeaturesArray(rootElement);

            for (JsonElement element : featuresArray) {
                JsonObject obj = element.getAsJsonObject();
                
                String name = getStringOrDefault(obj, "name", "");
                String title = getStringOrDefault(obj, "title", name);
                String description = getStringOrDefault(obj, "description", "");
                String category = getStringOrDefault(obj, "category", "Other");
                boolean preview = obj.has("preview") && obj.get("preview").getAsBoolean();
                boolean community = obj.has("community") && obj.get("community").getAsBoolean();
                
                features.add(new Feature(name, title, description, category, preview, community));
            }
        } catch (Exception e) {
            System.err.println("Warning: Failed to parse features: " + e.getMessage());
        }
        
        Collections.sort(features, (a, b) -> {
            int catCompare = a.category().compareTo(b.category());
            if (catCompare != 0) return catCompare;
            return a.title().compareTo(b.title());
        });
        
        return features;
    }

    private String getStringOrDefault(JsonObject obj, String key, String defaultValue) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return defaultValue;
    }

    private SelectOptions parseSelectOptions(String json) {
        JsonElement rootElement = GSON.fromJson(json, JsonElement.class);
        if (rootElement == null || !rootElement.isJsonObject()) {
            return new SelectOptions(List.of(), List.of(), List.of(), List.of(), List.of());
        }

        JsonObject root = rootElement.getAsJsonObject();
        List<Option> types = parseOptionGroup(root, "type", "types", false);
        List<Option> langs = parseOptionGroup(root, "lang", "languages", false);
        List<Option> builds = parseOptionGroup(root, "build", "builds", false);
        List<Option> tests = parseOptionGroup(root, "test", "tests", false);
        List<Option> jdkVersions = parseOptionGroup(root, "jdkVersion", "javaVersions", true);

        return new SelectOptions(types, langs, builds, tests, jdkVersions);
    }

    private List<Option> parseOptionGroup(JsonObject root, String singularKey, String legacyPluralKey, boolean jdkVersion) {
        JsonArray options = new JsonArray();

        if (root.has(singularKey) && root.get(singularKey).isJsonObject()) {
            JsonObject group = root.getAsJsonObject(singularKey);
            if (group.has("options") && group.get("options").isJsonArray()) {
                options = group.getAsJsonArray("options");
            }
        } else if (root.has(legacyPluralKey) && root.get(legacyPluralKey).isJsonArray()) {
            options = root.getAsJsonArray(legacyPluralKey);
        }

        List<Option> parsed = new ArrayList<>();
        for (JsonElement element : options) {
            if (!element.isJsonObject()) {
                continue;
            }
            JsonObject obj = element.getAsJsonObject();

            String label = firstNonBlank(
                asString(obj, "label"),
                asString(obj, "title"),
                asString(obj, "name"),
                asString(obj, "value")
            );
            String value = resolveOptionValue(obj, label, jdkVersion);
            String description = firstNonBlank(asString(obj, "description"), "");

            if (!label.isBlank() && !value.isBlank()) {
                parsed.add(new Option(label, value, description));
            }
        }

        return parsed;
    }

    private String resolveOptionValue(JsonObject obj, String label, boolean jdkVersion) {
        String name = asString(obj, "name");
        String value = asString(obj, "value");

        if (jdkVersion) {
            if (label.matches("\\d+")) {
                return label;
            }
            String fromName = extractJdkVersionNumber(name);
            if (!fromName.isBlank()) {
                return fromName;
            }
            String fromValue = extractJdkVersionNumber(value);
            if (!fromValue.isBlank()) {
                return fromValue;
            }
        }

        if (!name.isBlank()) {
            return normalizeEnumName(name);
        }
        if (!value.isBlank()) {
            return normalizeEnumName(value);
        }
        return normalizeEnumName(label);
    }

    private String normalizeEnumName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        if (raw.matches("[A-Z0-9_]+")) {
            return raw.toLowerCase(Locale.ROOT).replace('_', '-');
        }
        return raw;
    }

    private String extractJdkVersionNumber(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String normalized = raw.toUpperCase(Locale.ROOT);
        if (normalized.startsWith("JDK_")) {
            String candidate = normalized.substring(4);
            if (candidate.matches("\\d+")) {
                return candidate;
            }
        }
        return "";
    }

    private String asString(JsonObject obj, String key) {
        if (obj.has(key) && !obj.get(key).isJsonNull()) {
            return obj.get(key).getAsString();
        }
        return "";
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    public void clearCache() {
        featureCache.clear();
    }

    private JsonArray extractFeaturesArray(JsonElement rootElement) {
        if (rootElement == null || rootElement.isJsonNull()) {
            return new JsonArray();
        }
        if (rootElement.isJsonArray()) {
            return rootElement.getAsJsonArray();
        }
        if (rootElement.isJsonObject()) {
            JsonObject rootObj = rootElement.getAsJsonObject();
            if (rootObj.has("features") && rootObj.get("features").isJsonArray()) {
                return rootObj.getAsJsonArray("features");
            }
        }
        return new JsonArray();
    }

    private String buildProjectEndpoint(String action, ProjectConfig config) {
        String basePath = BASE_URL + "/" + action + "/" + encodePathSegment(config.getType()) + "/"
            + encodePathSegment(config.getName());
        String query = buildQuery(config);
        return basePath + "?" + query;
    }

    private String buildQuery(ProjectConfig config) {
        StringJoiner joiner = new StringJoiner("&");
        joiner.add(queryParam("lang", config.getLang()));
        joiner.add(queryParam("build", config.getBuild()));
        joiner.add(queryParam("test", config.getTest()));
        joiner.add(queryParam("javaVersion", config.getJdkVersion()));
        if (!config.getFeatures().isEmpty()) {
            joiner.add(queryParam("features", String.join(",", config.getFeatures())));
        }
        if (config.getBasePackage() != null && !config.getBasePackage().isBlank()) {
            joiner.add(queryParam("package", config.getBasePackage()));
        }
        return joiner.toString();
    }

    private String queryParam(String key, String value) {
        return encodeQueryValue(key) + "=" + encodeQueryValue(value);
    }

    private String encodePathSegment(String value) {
        String safeValue = value == null ? "" : value;
        return URLEncoder.encode(safeValue, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String encodeQueryValue(String value) {
        String safeValue = value == null ? "" : value;
        return URLEncoder.encode(safeValue, StandardCharsets.UTF_8);
    }
}
