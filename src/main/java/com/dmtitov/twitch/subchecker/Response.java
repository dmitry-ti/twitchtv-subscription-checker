package com.dmtitov.twitch.subchecker;

public class Response {
	private int code;
	private String message;
	private String body;
	
	public int getCode() {
		return code;
	}
	public void setCode(int statusCode) {
		this.code = statusCode;
	}
	public String getMessage() {
		return message;
	}
	public void setMessage(String message) {
		this.message = message;
	}
	public String getBody() {
		return body;
	}
	public void setBody(String body) {
		this.body = body;
	}
	@Override
	public String toString() {
		return "code: " + code + ", message: " + message + ", body: " + body;
	}
}
