package com.dmtitov.twitch.subchecker;

import static com.dmtitov.twitch.subchecker.SubscriptionStatus.NOT_SUBSCRIBED;
import static com.dmtitov.twitch.subchecker.SubscriptionStatus.SUBSCRIBED;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.logging.Logger;

import javax.net.ssl.HttpsURLConnection;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONArray;
import org.json.JSONObject;

public class AuthServlet extends HttpServlet {
	private static final String USER_AGENT = "Mozilla/5.0";
	private static final Logger LOGGER = Logger.getLogger(AuthServlet.class.getName());
	
	@Override
	public void doGet(HttpServletRequest request, HttpServletResponse response) throws IOException {
		LOGGER.info("processing get request");

		Properties properties = new Properties();
		try(InputStream is = new FileInputStream(Paths.get(System.getProperty("catalina.base"), "webapps", "subform.properties").toString())) {
			properties.load(is);
			LOGGER.info("properties: " + properties.toString());
		} catch(IOException e) {
			LOGGER.severe("could not load properties: " + e);
			return;
		}
		
		final String clientId = properties.getProperty("client_id");
		final String clientSecret = properties.getProperty("client_secret");
		final String redirectUri = properties.getProperty("redirect_uri");
		final String channelName = properties.getProperty("channel_name");
		final String code = request.getParameter("code");
		final String scope = request.getParameter("scope");

		Token token = getToken(clientId, clientSecret, redirectUri, code);
		if(token == null) {
			response.getWriter().println("<html><body>Server error: Could not get access token</body></html>");
			return;
		}

		User user = getUser(clientId, token.getAccessToken());
		if(user == null) {
			response.getWriter().println("<html><body>Server error: Could not get user information</body></html>");
			return;
		}

		Channel channel = getChannel(clientId, channelName, token.getAccessToken());
		if(channel == null) {
			response.getWriter().println("<html><body>Server error: Could not get channel information</body></html>");
			return;
		}

		Subscription subscription = new Subscription();
		SubscriptionStatus subscriptionStatus = getSubscriptionStatus(clientId, token.getAccessToken(), user.getId(), channel.getId(), subscription);
		if(subscriptionStatus == null) {
			response.getWriter().println("<html><body>Server error: Could not get subscription status</body></html>");
			return;
		} else if(subscriptionStatus == NOT_SUBSCRIBED) {
			response.getWriter().println("<html><body>User is not subscribed to the channel</body></html>");
			return;
		}

		response.getWriter()
				.append("<html>").append("<body>").append("code: ").append(code).append("<br>")
				.append("scope: ").append(scope).append("<br>").append("access token: ").append(token.getAccessToken())
				.append("<br>").append("refresh token: ").append(token.getRefreshToken()).append("<br>")
				.append("user id: ").append(user.getId()).append("<br>").append("user email: ").append(user.getEmail())
				.append("<br>").append("user name: ").append(user.getName()).append("<br>")
				.append("user displayed name: ").append(user.getDisplayedName()).append("<br>")
				.append("subscription plan name: ").append(subscription.getPlanName()).append("<br>")
				.append("channel id: ").append(subscription.getChannel().getId()).append("<br>")
				.append("channel name: ").append(subscription.getChannel().getName()).append("</body>")
				.append("</html>").flush();
	}

	private Token getToken(String clientId, String clientSecret, String redirectUri, String code) {
		try {
			LOGGER.info("getting token");
			LOGGER.info("code: " + code);

			HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/kraken/oauth2/token").openConnection();
			connection.setRequestMethod("POST");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setDoOutput(true);

			StringBuilder sb = new StringBuilder();
			sb.append("client_id=").append(clientId);
			sb.append("&");
			sb.append("client_secret=").append(clientSecret);
			sb.append("&");
			sb.append("code=").append(code);
			sb.append("&");
			sb.append("grant_type=authorization_code");
			sb.append("&");
			sb.append("redirect_uri=").append(redirectUri);

			DataOutputStream wr = new DataOutputStream(connection.getOutputStream());
			wr.writeBytes(sb.toString());
			wr.flush();
			wr.close();

			Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			JSONObject jsonResponse = new JSONObject(response.getBody());
			Token token = new Token();
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

			HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/kraken/user").openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/vnd.twitchtv.v5+json");
			connection.setRequestProperty("Client-ID", clientId);
			connection.setRequestProperty("Authorization", "OAuth " + accessToken);

			Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			JSONObject jsonResponse = new JSONObject(response.getBody());
			User user = new User();
			user.setId(jsonResponse.getString("_id"));
			user.setEmail(jsonResponse.getString("email"));
			user.setName(jsonResponse.getString("name"));
			user.setDisplayedName(jsonResponse.getString("display_name"));
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

			HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/helix/users?login=" + channelName).openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Client-ID", clientId);
			connection.setRequestProperty("Authorization", "Bearer " + accessToken);

			Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			JSONObject jsonResponse = new JSONObject(response.getBody());
			JSONArray jsonData = jsonResponse.getJSONArray("data");
			JSONObject jsonChannel = jsonData.getJSONObject(0);
			Channel channel = new Channel();
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

			HttpsURLConnection connection = (HttpsURLConnection)new URL("https://api.twitch.tv/kraken/users/" + userId + "/subscriptions/" + channelId).openConnection();
			connection.setRequestMethod("GET");
			connection.setRequestProperty("User-Agent", USER_AGENT);
			connection.setRequestProperty("Accept", "application/vnd.twitchtv.v5+json");
			connection.setRequestProperty("Client-ID", clientId);
			connection.setRequestProperty("Authorization", "OAuth " + accessToken);

			Response response = getResponse(connection);
			LOGGER.info("response: " + response);
			if(response.getCode() == HTTP_NOT_FOUND) {
				return NOT_SUBSCRIBED;
			} else if(response.getCode() != HTTP_OK) {
				LOGGER.severe("unexpected http status code: " + response.getCode());
				return null;
			}
			JSONObject jsonResponse = new JSONObject(response.getBody());
			subscription.setPlanName(jsonResponse.getString("sub_plan_name"));
			JSONObject jsonChannel = jsonResponse.getJSONObject("channel");
			Channel channel = new Channel();
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
		Response response = new Response();
		response.setCode(connection.getResponseCode());
		response.setMessage(connection.getResponseMessage());
		ByteArrayOutputStream responseOutputStream = new ByteArrayOutputStream();
		byte[] buffer = new byte[1024];
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
}
