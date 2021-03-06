package com.dmtitov.twitch.subchecker;

import static com.dmtitov.twitch.subchecker.SubscriptionStatus.NOT_SUBSCRIBED;
import static com.dmtitov.twitch.subchecker.SubscriptionStatus.SUBSCRIBED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.text.StrSubstitutor;
import org.json.JSONArray;
import org.json.JSONObject;

public class AuthServlet extends HttpServlet {
	private static final String USER_AGENT = "Mozilla/5.0";
	private static final String USERNAME_PARAM_NAME = "username";
	private static final String EMAIL_PARAM_NAME = "email";
	private static final String LOGO_PARAM_NAME = "logo";
	private static final String SUB_DATE_PARAM_NAME = "subdate";
	
	private static final Logger LOGGER = Logger.getLogger(AuthServlet.class.getName());
	
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		LOGGER.info("processing get request");

		final Properties properties = new Properties();
		try(InputStream is = new FileInputStream(Paths.get(System.getProperty("catalina.base"), "webapps", "subchecker.properties").toString())) {
			properties.load(is);
			LOGGER.info("properties: " + properties.toString());
		} catch(IOException e) {
			LOGGER.info("could not load properties: " + e);
		}
		
		final String clientId = getPropertyValue("SUBCHECKER_TWITCH_APP_CLIENT_ID", "twitch.app.client_id", properties);
		final String clientSecret = getPropertyValue("SUBCHECKER_TWITCH_APP_CLIENT_SECRET", "twitch.app.client_secret", properties);
		final String twitchAppRedirectUri = getPropertyValue("SUBCHECKER_TWITCH_APP_REDIRECT_URI", "twitch.app.redirect_uri", properties);
		final String channelName = getPropertyValue("SUBCHECKER_TWITCH_CHANNEL_NAME", "twitch.channel_name", properties);
		final String onSuccessRedirectUrl = getPropertyValue("SUBCHECKER_ON_SUCCESS_REDIRECT_URL", "on_success_redirect_url", properties);

		if(isBlank(clientId) || isBlank(clientSecret) || isBlank(twitchAppRedirectUri) || isBlank(channelName) || isBlank(onSuccessRedirectUrl)) {
			response.getWriter().println("<html><body>Server error: Wrong configuration</body></html>");
			LOGGER.severe("Server error: Wrong configuration");
			return;
		}
		
		final String code = request.getParameter("code");
		if(isBlank(code)) {
			response.getWriter().println("<html><body>Server error: Wrong request parameters</body></html>");
			LOGGER.severe("Server error: Wrong request parameters");
			return;
		}

		final Token token = getToken(clientId, clientSecret, twitchAppRedirectUri, code);
		if(token == null) {
			response.getWriter().println("<html><body>Server error: Could not get access token</body></html>");
			LOGGER.severe("Server error: Could not get access token");
			return;
		}

		final User user = getUser(clientId, token.getAccessToken());
		if(user == null) {
			response.getWriter().println("<html><body>Server error: Could not get user information</body></html>");
			LOGGER.severe("Server error: Could not get user information");
			return;
		}

		final Channel channel = getChannel(clientId, channelName, token.getAccessToken());
		if(channel == null) {
			response.getWriter().println("<html><body>Server error: Could not get channel information</body></html>");
			LOGGER.severe("Server error: Could not get channel information");
			return;
		}

		final Subscription subscription = new Subscription();
		final SubscriptionStatus subscriptionStatus = getSubscriptionStatus(clientId, token.getAccessToken(), user.getId(), channel.getId(), subscription);
		if(subscriptionStatus == null) {
			response.getWriter().println("<html><body>Server error: Could not get subscription status</body></html>");
			LOGGER.severe("Server error: Could not get subscription status");
			return;
		} else if(subscriptionStatus == NOT_SUBSCRIBED) {
			response.getWriter().println("<html><body>User is not subscribed to the channel</body></html>");
			LOGGER.info("User is not subscribed to the channel");
			return;
		}

		response.setStatus(HttpServletResponse.SC_MOVED_PERMANENTLY);
		final String onSuccessRedirectUrlWithParams = buildOnSuccessRedirectUrlWithParams(onSuccessRedirectUrl, user, subscription);
		response.setHeader("Location", onSuccessRedirectUrlWithParams);
		LOGGER.info("Performing redirect to: " + onSuccessRedirectUrlWithParams);
	}

	private Token getToken(String clientId, String clientSecret, String redirectUri, String code) {
		try {
			LOGGER.info("getting token");
			LOGGER.info("code: " + code);

			final HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/kraken/oauth2/token").openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setDoOutput(true);

			final StringBuilder sb = new StringBuilder();
			sb.append("client_id=").append(clientId);
			sb.append("&");
			sb.append("client_secret=").append(clientSecret);
			sb.append("&");
			sb.append("code=").append(code);
			sb.append("&");
			sb.append("grant_type=authorization_code");
			sb.append("&");
			sb.append("redirect_uri=").append(redirectUri);

			final DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
			wr.writeBytes(sb.toString());
			wr.flush();
			wr.close();

			final Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			final JSONObject jsonResponse = new JSONObject(response.getBody());
			final Token token = new Token();
			token.setAccessToken(jsonResponse.getString("access_token"));
			token.setRefreshToken(jsonResponse.getString("refresh_token"));
			return token;
		} catch(Exception e) {
			LOGGER.severe("could not get token: " + e);
			return null;
		}
	}

	private User getUser(String clientId, String accessToken) {
		try {
			LOGGER.info("getting user");
			LOGGER.info("accessToken: " + accessToken);

			final HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/kraken/user").openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/vnd.twitchtv.v5+json");
			connection.setRequestProperty("Client-ID", clientId);
			connection.setRequestProperty("Authorization", "OAuth " + accessToken);

			final Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			final JSONObject jsonResponse = new JSONObject(response.getBody());
			final User user = new User();
			user.setId(jsonResponse.getString("_id"));
			user.setEmail(jsonResponse.getString("email"));
			user.setName(jsonResponse.getString("name"));
			user.setDisplayedName(jsonResponse.getString("display_name"));
			user.setLogo(jsonResponse.getString("logo"));
			return user;
		} catch(Exception e) {
			LOGGER.severe("could not get user: " + e);
			return null;
		}
	}

	private Channel getChannel(String clientId, String channelName, String accessToken) {
		try {
			LOGGER.info("getting channel");
			LOGGER.info("accessToken: " + accessToken + ", channelName: " + channelName);

			final HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/helix/users?login=" + channelName).openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Client-ID", clientId);
			connection.setRequestProperty("Authorization", "Bearer " + accessToken);

			final Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			final JSONObject jsonResponse = new JSONObject(response.getBody());
			final JSONArray jsonData = jsonResponse.getJSONArray("data");
			final JSONObject jsonChannel = jsonData.getJSONObject(0);
			final Channel channel = new Channel();
			channel.setId(jsonChannel.getString("id"));
			channel.setName(jsonChannel.getString("login"));
			return channel;
		} catch(Exception e) {
			LOGGER.severe("could not get channel: " + e);
			return null;
		}
	}

	private SubscriptionStatus getSubscriptionStatus(String clientId, String accessToken, String userId, String channelId, Subscription subscription) {
		try {
			LOGGER.info("getting subscription status");
			LOGGER.info("accessToken: " + accessToken + ", userId: " + userId + ", channelId: " + channelId);

			final HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/kraken/users/" + userId + "/subscriptions/" + channelId).openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/vnd.twitchtv.v5+json");
			connection.setRequestProperty("Client-ID", clientId);
			connection.setRequestProperty("Authorization", "OAuth " + accessToken);

			final Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() == HTTP_NOT_FOUND) {
				return NOT_SUBSCRIBED;
			} else if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			final JSONObject jsonResponse = new JSONObject(response.getBody());
			subscription.setPlanName(jsonResponse.getString("sub_plan_name"));
			subscription.setDateTime(LocalDateTime.parse(jsonResponse.getString("created_at"), DateTimeFormatter.ISO_DATE_TIME));
			final JSONObject jsonChannel = jsonResponse.getJSONObject("channel");
			final Channel channel = new Channel();
			channel.setId(jsonChannel.getString("_id"));
			channel.setName(jsonChannel.getString("name"));
			subscription.setChannel(channel);
			return SUBSCRIBED;
		} catch(Exception e) {
			LOGGER.severe("could not get subscription status: " + e);
			return null;
		}
	}

	private Response getResponse(HttpsURLConnection connection) throws IOException, UnsupportedEncodingException {
		final Response response = new Response();
		response.setCode(connection.getResponseCode());
		response.setMessage(connection.getResponseMessage());
		final ByteArrayOutputStream responseOutputStream = new ByteArrayOutputStream();
		final byte[] buffer = new byte[1024];
		int length;
		InputStream is;
		if(response.getCode() < 400) {
			is = connection.getInputStream();
		} else {
			is = connection.getErrorStream();
		}
		while((length = is.read(buffer)) != -1) {
			responseOutputStream.write(buffer, 0, length);
		}
		response.setBody(responseOutputStream.toString(UTF_8.name()));
		return response;
	}
	
	private String getPropertyValue(String environmentVariableName, String propertyName, Properties properties) {
		final String env = System.getenv(environmentVariableName);
		if(isNotBlank(env)) {
			LOGGER.info("Using environment variable: " + environmentVariableName + "=" + env);
			return env;
		}
		final String prop = properties.getProperty(propertyName);
		LOGGER.info("Using property: " + propertyName + "=" + prop);
		return prop;
	}
	
	private String buildOnSuccessRedirectUrlWithParams(String onSuccessRedirectUrl, User user, Subscription subscription) {
		final Map<String, String> paramsMap = new HashMap<>();
		addParam(paramsMap, USERNAME_PARAM_NAME, user.getDisplayedName());
		addParam(paramsMap, EMAIL_PARAM_NAME, user.getEmail());
		addParam(paramsMap, LOGO_PARAM_NAME, user.getLogo());
		addParam(paramsMap, SUB_DATE_PARAM_NAME, subscription.getDateTime().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")));
		return new StrSubstitutor(paramsMap).replace(onSuccessRedirectUrl);
	}
	
	private void addParam(Map<String, String> paramsMap, String name, String value) {
		try {
			paramsMap.put(name, URLEncoder.encode(value, StandardCharsets.UTF_8.toString()));
		} catch(UnsupportedEncodingException e) {
			LOGGER.warning("Could not encode value: '" + value + "' for param '" + name + "'");
		}
	}
}
