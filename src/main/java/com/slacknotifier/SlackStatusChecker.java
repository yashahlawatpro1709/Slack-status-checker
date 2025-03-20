package com.slacknotifier;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;
import java.util.concurrent.TimeUnit;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@ApplicationScoped
public class SlackStatusChecker {

    @ConfigProperty(name = "slack.token")
    String token;

    @ConfigProperty(name = "slack.user.id")
    String userId;

    private static final String SLACK_API_URL = "https://slack.com/api/users.getPresence";
    private String lastStatus = "unknown";

    @Scheduled(every = "1s")
    public void checkStatus() {
        String currentStatus = getUserStatus();
        System.out.println("Checking status... Current: " + currentStatus + ", Last: " + lastStatus);

        if (!currentStatus.equals(lastStatus)) {
            notifyStatusChange(lastStatus, currentStatus);
            lastStatus = currentStatus;
        }
    }

    private String getUserStatus() {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(SLACK_API_URL + "?user=" + userId))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var jsonResponse = new JSONObject(response.body());

            return jsonResponse.optString("presence", "unknown");
        } catch (Exception e) {
            e.printStackTrace();
            return "error";
        }
    }

    private void notifyStatusChange(String oldStatus, String newStatus) {
        try {
            String statusEmoji = newStatus.equals("active") ? "🟢" : "🔴";
            String statusMessage = newStatus.equals("active") ? "User is now ONLINE" : "User is now OFFLINE";
            
            // Using terminal-notifier for more reliable macOS notifications
            String[] cmd = {
                "terminal-notifier",
                "-title", statusEmoji + " Slack Alert",
                "-message", statusMessage,
                "-sound", "default"
            };
            
            Runtime.getRuntime().exec(cmd);
            
        } catch (Exception e) {
            // Try fallback to osascript if terminal-notifier fails
            try {
                String[] fallbackCmd = {
                    "osascript",
                    "-e",
                    "display notification \"" + (newStatus.equals("active") ? "User is now ONLINE" : "User is now OFFLINE") + 
                    "\" with title \"Slack Alert\" sound name \"Glass\""
                };
                Runtime.getRuntime().exec(fallbackCmd);
            } catch (Exception ex) {
                System.err.println("Notification failed: " + ex.getMessage());
            }
        }
    }
}
