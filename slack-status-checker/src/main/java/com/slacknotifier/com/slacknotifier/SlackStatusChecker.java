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

    private static final String SLACK_USER_INFO_URL = "https://slack.com/api/users.info";

    private boolean checkHuddleStatus() {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(SLACK_USER_INFO_URL + "?user=" + userId))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var jsonResponse = new JSONObject(response.body());
            
            if (jsonResponse.getBoolean("ok")) {
                JSONObject user = jsonResponse.getJSONObject("user");
                JSONObject profile = user.getJSONObject("profile");
                String huddleState = profile.optString("huddle_state", "");
                System.out.println("Current huddle state: " + huddleState); // Debug log
                return "in_a_huddle".equals(huddleState);
            }
            return false;
        } catch (Exception e) {
            System.err.println("Failed to check huddle status: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void notifyStatusChange(String oldStatus, String newStatus) {
        try {
            String statusEmoji = newStatus.equals("active") ? "🟢" : "🔴";
            String statusMessage = String.format("%s is now %s", userName, newStatus.equals("active") ? "ONLINE" : "OFFLINE");
            
            String[] cmd = {
                "terminal-notifier",
                "-title", statusEmoji + " " + userName + "'s Status",
                "-message", statusMessage,
                "-sound", "default"
            };
            
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            System.err.println("Notification failed: " + e.getMessage());
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

    private String userName = "Unknown User";
    private static final int WORKING_HOURS = 8;
    private long totalOnlineTime = 0;
    private long totalHuddleTime = 0;
    private long lastStatusCheckTime = System.currentTimeMillis();

    @jakarta.annotation.PostConstruct
    public void init() {
        fetchUserInfo();
    }

    private void fetchUserInfo() {
        try {
            var client = HttpClient.newHttpClient();
            var request = HttpRequest.newBuilder()
                    .uri(java.net.URI.create(SLACK_USER_INFO_URL + "?user=" + userId))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();

            var response = client.send(request, HttpResponse.BodyHandlers.ofString());
            var jsonResponse = new JSONObject(response.body());
            
            if (jsonResponse.getBoolean("ok")) {
                JSONObject user = jsonResponse.getJSONObject("user");
                userName = user.getString("real_name");
            }
        } catch (Exception e) {
            System.err.println("Failed to fetch user info: " + e.getMessage());
        }
    }

    @Scheduled(every = "1s")
    public void checkStatus() {
        String currentStatus = getUserStatus();
        System.out.println("Checking status... Current: " + currentStatus + ", Last: " + lastStatus);

        if (!currentStatus.equals(lastStatus)) {
            notifyStatusChange(lastStatus, currentStatus);
            lastStatus = currentStatus;
        }
    }

    // Add these fields at the top with other fields
    private static final int PERIODS_PER_DAY = 24; // 24 one-hour periods
    private int[] onlineCountPerPeriod = new int[PERIODS_PER_DAY];
    private int[] huddleCountPerPeriod = new int[PERIODS_PER_DAY];
    private int[] totalChecksPerPeriod = new int[PERIODS_PER_DAY];
    
    // Add this method
    private void updateEfficiencyMetrics() {
        int currentHour = java.time.LocalDateTime.now().getHour();
        totalChecksPerPeriod[currentHour]++;
        
        String currentStatus = getUserStatus();
        boolean inHuddle = checkHuddleStatus();
        
        if (currentStatus.equals("active")) {
            onlineCountPerPeriod[currentHour]++;
        }
        if (inHuddle) {
            huddleCountPerPeriod[currentHour]++;
        }
        
        analyzeEfficiencyPatterns();
    }
    
    private void analyzeEfficiencyPatterns() {
        double maxEfficiency = 0.0;
        int mostProductiveHour = 0;
        StringBuilder report = new StringBuilder("Efficiency Patterns:\n\n");
        
        for (int hour = 0; hour < PERIODS_PER_DAY; hour++) {
            if (totalChecksPerPeriod[hour] == 0) continue;
            
            double onlineRatio = (double) onlineCountPerPeriod[hour] / totalChecksPerPeriod[hour];
            double huddleRatio = (double) huddleCountPerPeriod[hour] / totalChecksPerPeriod[hour];
            double efficiency = (onlineRatio * 0.7) + (huddleRatio * 0.3);
            
            if (efficiency > maxEfficiency) {
                maxEfficiency = efficiency;
                mostProductiveHour = hour;
            }
            
            if (efficiency > 0.3) { // Only report significant activity periods
                report.append(String.format("%02d:00 - %02d:00: %.2f%% efficiency\n", 
                    hour, (hour + 1) % 24, efficiency * 100));
            }
        }
        
        report.append(String.format("\nMost Productive Hour: %02d:00 - %02d:00 (%.2f%% efficiency)", 
            mostProductiveHour, (mostProductiveHour + 1) % 24, maxEfficiency * 100));
        
        notifyEfficiencyPatterns(report.toString());
    }
    
    private void notifyEfficiencyPatterns(String patterns) {
        try {
            String[] cmd = {
                "terminal-notifier",
                "-title", "⏰ " + userName + "'s Efficiency Patterns",
                "-message", patterns,
                "-sound", "default"
            };
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            System.err.println("Failed to send efficiency patterns notification: " + e.getMessage());
        }
    }

    // Modify the checkProductivity method to include efficiency metrics
    @Scheduled(every = "10s")
    public void checkProductivity() {
        String currentStatus = getUserStatus();
        boolean inHuddle = checkHuddleStatus();
        long currentTime = System.currentTimeMillis();
        long timeElapsed = (currentTime - lastStatusCheckTime) / 1000;

        if (currentStatus.equals("active")) {
            totalOnlineTime += timeElapsed;
        }
        if (inHuddle) {
            totalHuddleTime += timeElapsed;
        }

        updateEfficiencyMetrics(); // Add this line before the end of the method
        
        double productivityScore = calculateProductivity();
        notifyProductivity(productivityScore);
        lastStatusCheckTime = currentTime;
    }

    private double calculateProductivity() {
        double workingSeconds = WORKING_HOURS * 3600; 
        double onlinePercentage = (totalOnlineTime / workingSeconds) * 100;
        double huddlePercentage = (totalHuddleTime / workingSeconds) * 100;
        
        return (onlinePercentage * 0.7) + (huddlePercentage * 0.3);
    }

    private void notifyProductivity(double productivity) {
        String productivityMessage = String.format("""
            %s's Productivity:
            Online Time: %.2f hours
            Huddle Time: %.2f hours
            Productivity Score: %.2f%%
            """, 
            userName,
            totalOnlineTime / 3600.0,
            totalHuddleTime / 3600.0,
            productivity);

        try {
            String[] cmd = {
                "terminal-notifier",
                "-title", "📊 " + userName + "'s Productivity",
                "-message", productivityMessage,
                "-sound", "default"
            };
            Runtime.getRuntime().exec(cmd);
        } catch (Exception e) {
            System.err.println("Failed to send productivity notification: " + e.getMessage());
        }
    }
}
