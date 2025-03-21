package com.slacknotifier;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.users.UsersListResponse;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.websocket.*;
import jakarta.websocket.server.ServerEndpoint;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

// Add these imports at the top
import java.time.Duration;
import com.slack.api.methods.request.users.UsersListRequest;
import java.io.StringReader;
import com.slack.api.methods.response.users.UsersInfoResponse;
import com.slack.api.model.User;

// Add this import at the top
import io.quarkus.scheduler.Scheduled;

@ServerEndpoint("/status")  // Changed back to original endpoint
@ApplicationScoped
public class WebSocketEndpoint {
    private static Set<Session> sessions = Collections.synchronizedSet(new HashSet<>());
    private static final int MAX_RETRY_ATTEMPTS = 3;
    private static final long RETRY_DELAY = 2000;
    private static final long STATUS_CHECK_INTERVAL = 5000; // 5 seconds
    private volatile boolean isUserVerified = false;

    @Inject
    @ConfigProperty(name = "slack.token")
    String slackToken;

    @Inject
    @ConfigProperty(name = "slack.user.id")
    String userId;

    private final Slack slack = Slack.getInstance();

    @OnOpen
    public void onOpen(Session session) {
        try {
            System.out.println("WebSocket session opened: " + session.getId());
            sessions.add(session);
            session.setMaxIdleTimeout(Duration.ofMinutes(30).toMillis());
            
            session.setMaxTextMessageBufferSize(32768);
            session.setMaxBinaryMessageBufferSize(32768);
            
            // Verify user first
            if (!isUserVerified) {
                verifyUser();
            }
            
            if (isUserVerified) {
                sendUserInfoWithRetry(session, 0);
            } else {
                sendErrorMessage(session, "Invalid user configuration");
            }
        } catch (Exception e) {
            System.err.println("Error in onOpen: " + e.getMessage());
            e.printStackTrace();
            sendErrorMessage(session, "Failed to initialize connection");
        }
    }

    private void verifyUser() {
        try {
            MethodsClient methods = slack.methods(slackToken);
            var userResponse = methods.usersInfo(r -> r
                .token(slackToken)
                .user(userId));

            if (userResponse.isOk() && userResponse.getUser() != null) {
                isUserVerified = true;
                System.out.println("User verified successfully: " + userResponse.getUser().getRealName());
            } else {
                System.err.println("User verification failed: " + userResponse.getError());
                isUserVerified = false;
            }
        } catch (Exception e) {
            System.err.println("Error verifying user: " + e.getMessage());
            isUserVerified = false;
        }
    }

    @Inject
    SlackStatusChecker slackStatusChecker;

    @Scheduled(every = "1m")
    public void sendPeriodicUpdates() {
        for (Session session : sessions) {
            if (session.isOpen()) {
                try {
                    // Get productivity metrics
                    double productivityScore = slackStatusChecker.calculateProductivity();
                    long onlineTime = slackStatusChecker.getTotalOnlineTime();
                    long huddleTime = slackStatusChecker.getTotalHuddleTime();

                    // Create metrics update object
                    JsonObject metricsUpdate = Json.createObjectBuilder()
                        .add("type", "metricsUpdate")
                        .add("onlineTime", onlineTime / 3600.0)
                        .add("huddleTime", huddleTime / 3600.0)
                        .add("productivityScore", productivityScore)
                        .add("hourlyMetrics", Json.createArrayBuilder(slackStatusChecker.getHourlyMetrics()))
                        .build();

                    session.getAsyncRemote().sendText(metricsUpdate.toString());
                } catch (Exception e) {
                    System.err.println("Error sending periodic update: " + e.getMessage());
                }
            }
        }
    }

    private void sendUserInfoWithRetry(Session session, int retryCount) {
        try {
            if (!isUserVerified) {
                sendErrorMessage(session, "User not configured correctly");
                return;
            }

            MethodsClient methods = slack.methods(slackToken);
            
            // Get user info and presence info
            var userResponse = methods.usersInfo(r -> r.token(slackToken).user(userId));
            var presenceResponse = methods.usersGetPresence(r -> r.token(slackToken).user(userId));

            if (!userResponse.isOk() || !presenceResponse.isOk()) {
                if (retryCount < MAX_RETRY_ATTEMPTS - 1) {
                    Thread.sleep(RETRY_DELAY);
                    sendUserInfoWithRetry(session, retryCount + 1);
                } else {
                    sendErrorMessage(session, "Failed to fetch user data");
                }
                return;
            }

            var member = userResponse.getUser();
            if (member != null) {
                // Get productivity metrics
                double productivityScore = slackStatusChecker.calculateProductivity();
                long onlineTime = slackStatusChecker.getTotalOnlineTime();
                long huddleTime = slackStatusChecker.getTotalHuddleTime();

                // Create efficiency object
                var efficiencyData = Json.createObjectBuilder()
                    .add("onlineTime", onlineTime / 3600.0) // Convert to hours
                    .add("huddleTime", huddleTime / 3600.0) // Convert to hours
                    .add("productivityScore", productivityScore)
                    .add("hourlyMetrics", Json.createArrayBuilder(slackStatusChecker.getHourlyMetrics()))
                    .build();

                JsonObject userInfo = Json.createObjectBuilder()
                    .add("type", "userInfo")
                    .add("status", presenceResponse.getPresence())
                    .add("huddle", "in_a_huddle".equals(member.getProfile().getHuddleState()))
                    .add("userName", member.getName())
                    .add("realName", member.getRealName())
                    .add("statusText", member.getProfile().getStatusText() != null ? member.getProfile().getStatusText() : "")
                    .add("statusEmoji", member.getProfile().getStatusEmoji() != null ? member.getProfile().getStatusEmoji() : "")
                    .add("imageUrl", member.getProfile().getImage192())
                    .add("efficiency", efficiencyData)
                    .build();

                System.out.println("Sending user info with efficiency data: " + userInfo);
                session.getAsyncRemote().sendText(userInfo.toString());
            }
        } catch (Exception e) {
            System.err.println("Error in sendUserInfoWithRetry: " + e.getMessage());
            if (retryCount < MAX_RETRY_ATTEMPTS - 1) {
                try {
                    Thread.sleep(RETRY_DELAY);
                    sendUserInfoWithRetry(session, retryCount + 1);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            } else {
                sendErrorMessage(session, "Error fetching user data");
            }
        }
    }

    @OnError
    public void onError(Session session, Throwable throwable) {
        System.err.println("WebSocket error occurred: " + throwable.getMessage());
        throwable.printStackTrace();
        sendErrorMessage(session, "WebSocket error: " + throwable.getMessage());
    }

    @OnMessage
    public void onMessage(String message, Session session) {
        try {
            JsonObject jsonMessage = Json.createReader(new StringReader(message)).readObject();
            if ("getStatus".equals(jsonMessage.getString("type"))) {
                System.out.println("Received getStatus request");
                sendUserInfoWithRetry(session, 0);
            }
        } catch (Exception e) {
            System.err.println("Error processing message: " + e.getMessage());
            e.printStackTrace();
            sendErrorMessage(session, "Error processing request: " + e.getMessage());
        }
    }

    @OnClose
    public void onClose(Session session) {
        sessions.remove(session);
    }

    private void sendUserInfo(Session session) {
        try {
            MethodsClient methods = slack.methods(slackToken);
            UsersListResponse response = methods.usersList(r -> r
                .token(slackToken)
                .limit(100));

            if (!response.isOk()) {
                System.err.println("Slack API error: " + response.getError());
                sendErrorMessage(session, "Failed to fetch user data");
                return;
            }

            response.getMembers().stream()
                .filter(member -> member.getId().equals(userId))
                .findFirst()
                .ifPresentOrElse(member -> {
                    try {
                        // Include current status and huddle information
                        JsonObject userInfo = Json.createObjectBuilder()
                            .add("type", "userInfo")
                            .add("current", member.getProfile().getStatusText() != null ? "active" : "away")
                            .add("huddle", member.getProfile() != null && "in_a_huddle".equals(member.getProfile().getHuddleState()))
                            .add("timezone", member.getTz() != null ? member.getTz() : "Unknown")
                            .add("timezoneLabel", member.getTzLabel() != null ? member.getTzLabel() : "Unknown")
                            .add("timezoneOffset", member.getTzOffset())
                            .add("statusText", member.getProfile() != null ? member.getProfile().getStatusText() : "")
                            .add("statusEmoji", member.getProfile() != null ? member.getProfile().getStatusEmoji() : "")
                            .add("realName", member.getProfile() != null ? member.getProfile().getRealName() : "Unknown")
                            .add("imageUrl", member.getProfile() != null ? member.getProfile().getImage192() : "")
                            .build();

                        session.getAsyncRemote().sendText(userInfo.toString());
                    } catch (Exception e) {
                        System.err.println("Error creating user info JSON: " + e.getMessage());
                        sendErrorMessage(session, "Error processing user data");
                    }
                }, () -> {
                    System.err.println("User not found: " + userId);
                    sendErrorMessage(session, "User not found");
                });
        } catch (Exception e) {
            System.err.println("Error fetching user info: " + e.getMessage());
            e.printStackTrace();
            sendErrorMessage(session, "Internal server error");
        }
    }

    private void sendErrorMessage(Session session, String errorMessage) {
        try {
            JsonObject error = Json.createObjectBuilder()
                .add("type", "error")
                .add("message", errorMessage)
                .build();
            session.getAsyncRemote().sendText(error.toString());
        } catch (Exception e) {
            System.err.println("Error sending error message: " + e.getMessage());
        }
    }

    public static void broadcast(String message) {
        sessions.forEach(session -> {
            session.getAsyncRemote().sendText(message);
        });
    }
}