package com.yuutara.illustrationarchive.service;

import com.yuutara.illustrationarchive.dto.XLikeCandidate;
import com.yuutara.illustrationarchive.dto.XLikeMedia;
import com.yuutara.illustrationarchive.dto.XLikePage;
import com.yuutara.illustrationarchive.dto.XLikeStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
public class XApiClient {
	private static final String LIKES_FIELDS = "?max_results=%d&expansions=author_id,attachments.media_keys"
			+ "&tweet.fields=author_id,attachments,created_at,text"
			+ "&user.fields=name,username&media.fields=media_key,type,url,width,height";

	private final HttpClient httpClient;
	private final ObjectMapper objectMapper;
	private final boolean enabled;
	private final String baseUrl;
	private final String accessToken;

	@Autowired
	public XApiClient(ObjectMapper objectMapper,
			@Value("${x.api.enabled:false}") boolean enabled,
			@Value("${x.api.base-url:https://api.x.com}") String baseUrl,
			@Value("${x.api.access-token:}") String accessToken) {
		this(HttpClient.newHttpClient(), objectMapper, enabled, baseUrl, accessToken);
	}

	XApiClient(HttpClient httpClient, ObjectMapper objectMapper, boolean enabled,
			String baseUrl, String accessToken) {
		this.httpClient = httpClient;
		this.objectMapper = objectMapper;
		this.enabled = enabled;
		this.baseUrl = baseUrl.replaceAll("/+$", "");
		this.accessToken = accessToken;
	}

	public XLikePage fetchRecentLikes(int maxResults) {
		if (maxResults < 5 || maxResults > 100) {
			throw new IllegalArgumentException("maxResults must be between 5 and 100.");
		}
		if (!enabled || accessToken == null || accessToken.isBlank()) {
			throw new XApiException("X API sync is disabled or its access token is missing.", null);
		}
		String userId = requiredText(get("/2/users/me").path("data"), "id");
		if (!userId.matches("[0-9]+")) {
			throw new XApiException("X API returned an invalid user id.", null);
		}
		JsonNode response = get("/2/users/" + userId + "/liked_tweets" + LIKES_FIELDS.formatted(maxResults));
		return normalize(response);
	}

	private JsonNode get(String path) {
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(Duration.ofSeconds(20))
				.header("Authorization", "Bearer " + accessToken)
				.header("Accept", "application/json")
				.GET().build();
		try {
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			int status = response.statusCode();
			if (status == 401 || status == 403) {
				throw new XApiException("X API rejected the access token or its permissions.", status);
			}
			if (status == 429) {
				throw new XApiException("X API rate limit reached; retry manually later.", status);
			}
			if (status < 200 || status >= 300) {
				throw new XApiException("X API request failed with HTTP " + status + ".", status);
			}
			return objectMapper.readTree(response.body());
		} catch (IOException e) {
			throw new XApiException("X API network or response error.", null);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new XApiException("X API request was interrupted.", null);
		}
	}

	private XLikePage normalize(JsonNode response) {
		Map<String, JsonNode> users = byId(response.path("includes").path("users"), "id");
		Map<String, JsonNode> media = byId(response.path("includes").path("media"), "media_key");
		List<XLikeCandidate> candidates = new ArrayList<>();
		JsonNode posts = response.path("data");
		if (!posts.isMissingNode() && !posts.isArray()) {
			throw new XApiException("X API returned an invalid Likes page.", null);
		}
		for (JsonNode post : posts) {
			String authorId = requiredText(post, "author_id");
			JsonNode author = users.get(authorId);
			if (author == null) {
				throw new XApiException("X API omitted an author expansion.", null);
			}
			JsonNode keys = post.path("attachments").path("media_keys");
			List<XLikeMedia> directMedia = new ArrayList<>();
			boolean allPhotos = keys.isArray() && !keys.isEmpty();
			if (keys.isArray()) {
				for (JsonNode keyNode : keys) {
					String key = keyNode.asText();
					JsonNode item = media.get(key);
					String type = item == null ? "unknown" : optionalText(item, "type");
					String url = item == null ? null : optionalText(item, "url");
					allPhotos &= "photo".equals(type) && url != null;
					directMedia.add(new XLikeMedia(key, directMedia.size(), type == null ? "unknown" : type,
							url, item == null ? null : optionalInt(item, "width"),
							item == null ? null : optionalInt(item, "height")));
				}
			}
			Instant createdAt = null;
			String created = optionalText(post, "created_at");
			if (created != null) {
				try {
					createdAt = Instant.parse(created);
				} catch (DateTimeParseException e) {
					throw new XApiException("X API returned an invalid post creation time.", null);
				}
			}
			candidates.add(new XLikeCandidate(requiredText(post, "id"), authorId,
					requiredText(author, "username"), requiredText(author, "name"),
					optionalText(post, "text"), createdAt,
					allPhotos ? XLikeStatus.PENDING : XLikeStatus.UNSUPPORTED, List.copyOf(directMedia)));
		}
		return new XLikePage(List.copyOf(candidates),
				optionalText(response.path("meta"), "next_token") != null);
	}

	private Map<String, JsonNode> byId(JsonNode nodes, String field) {
		Map<String, JsonNode> result = new HashMap<>();
		if (nodes.isArray()) {
			for (JsonNode node : nodes) {
				result.put(requiredText(node, field), node);
			}
		}
		return result;
	}

	private String requiredText(JsonNode node, String field) {
		String value = optionalText(node, field);
		if (value == null || value.isBlank()) {
			throw new XApiException("X API response is missing required field " + field + ".", null);
		}
		return value;
	}

	private String optionalText(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asText();
	}

	private Integer optionalInt(JsonNode node, String field) {
		JsonNode value = node.path(field);
		return value.isMissingNode() || value.isNull() ? null : value.asInt();
	}
}
