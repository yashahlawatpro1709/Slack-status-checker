package com.slacknotifier;

// Add these imports at the top
import java.util.List;
import java.util.ArrayList;
import jakarta.json.Json;
import jakarta.json.JsonObject;
import jakarta.json.JsonArrayBuilder;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.json.JSONObject;
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

    @Scheduled(every = "1m")
    public void checkStatus() {
        String currentStatus = getUserStatus();
        System.out.println("Checking status... Current: " + currentStatus + ", Last: " + lastStatus);

        if (!currentStatus.equals(lastStatus)) {
            notifyStatusChange(lastStatus, currentStatus);
            lastStatus = currentStatus;
        }
    }
    private static final int PERIODS_PER_DAY = 24;
    private int[] onlineCountPerPeriod = new int[PERIODS_PER_DAY];
    private int[] huddleCountPerPeriod = new int[PERIODS_PER_DAY];
    private int[] totalChecksPerPeriod = new int[PERIODS_PER_DAY];
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
    
    // Add these fields at the top with other fields
    private static final int MINIMUM_CHECKS_REQUIRED = 3;
    private long metricsStartTime = System.currentTimeMillis();
    
    private void analyzeEfficiencyPatterns() {
        double maxEfficiency = 0.0;
        int mostProductiveHour = 0;
        StringBuilder report = new StringBuilder();
        report.append("Efficiency Report\n\n");
        
        boolean hasData = false;
        for (int hour = 0; hour < PERIODS_PER_DAY; hour++) {
            if (totalChecksPerPeriod[hour] < MINIMUM_CHECKS_REQUIRED) continue;
            
            hasData = true;
            double onlineRatio = (double) onlineCountPerPeriod[hour] / totalChecksPerPeriod[hour];
            double huddleRatio = (double) huddleCountPerPeriod[hour] / totalChecksPerPeriod[hour];
            double efficiency = (onlineRatio * 0.7) + (huddleRatio * 0.3);
            
            if (efficiency > maxEfficiency) {
                maxEfficiency = efficiency;
                mostProductiveHour = hour;
            }
            
            String timeBlock = String.format("%02d:00 - %02d:00", hour, (hour + 1) % 24);
            String efficiencyBar = createEfficiencyBar(efficiency);
            report.append(String.format("%s | %s %.1f%% (%d checks)\n", 
                timeBlock, efficiencyBar, efficiency * 100, totalChecksPerPeriod[hour]));
        }
        
        if (!hasData) {
            report.append("Collecting data... Please wait for more samples.\n");
            report.append(String.format("Current hour checks: %d (need %d more)\n", 
                totalChecksPerPeriod[java.time.LocalDateTime.now().getHour()],
                MINIMUM_CHECKS_REQUIRED - totalChecksPerPeriod[java.time.LocalDateTime.now().getHour()]));
        } else {
            report.append(String.format("\nMost Productive: %02d:00 - %02d:00 (%.1f%%)", 
                mostProductiveHour, (mostProductiveHour + 1) % 24, maxEfficiency * 100));
        }
        
        // Debug print
        System.out.println("Efficiency Report Content:\n" + report.toString());
        
        notifyEfficiencyPatterns(report.toString());
    }
    
    private void notifyEfficiencyPatterns(String patterns) {
        try {
            String[] cmd = {
                "terminal-notifier",
                "-title", "Efficiency Report for " + userName,
                "-message", patterns,
                "-sound", "default"
            };
            Process process = Runtime.getRuntime().exec(cmd);
            // Add error handling
            java.io.BufferedReader errorReader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = errorReader.readLine()) != null) {
                System.err.println("Notification Error: " + line);
            }
        } catch (Exception e) {
            System.err.println("Failed to send efficiency patterns notification: " + e.getMessage());
            e.printStackTrace();
        }
    }
    @Scheduled(every = "1m")
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

        updateEfficiencyMetrics();
        
        double productivityScore = calculateProductivity();
        notifyProductivity(productivityScore);
        lastStatusCheckTime = currentTime;
    }

    // Change these methods to public
    public double calculateProductivity() {
        double workingSeconds = WORKING_HOURS * 3600; 
        double onlinePercentage = (totalOnlineTime / workingSeconds) * 100;
        double huddlePercentage = (totalHuddleTime / workingSeconds) * 100;
        
        return (onlinePercentage * 0.7) + (huddlePercentage * 0.3);
    }

    public long getTotalOnlineTime() {
        return totalOnlineTime;
    }

    public long getTotalHuddleTime() {
        return totalHuddleTime;
    }

    public List<JsonObject> getHourlyMetrics() {
        List<JsonObject> metrics = new ArrayList<>();
        for (int hour = 0; hour < PERIODS_PER_DAY; hour++) {
            if (totalChecksPerPeriod[hour] >= MINIMUM_CHECKS_REQUIRED) {
                double onlineRatio = (double) onlineCountPerPeriod[hour] / totalChecksPerPeriod[hour];
                double huddleRatio = (double) huddleCountPerPeriod[hour] / totalChecksPerPeriod[hour];
                double efficiency = (onlineRatio * 0.7) + (huddleRatio * 0.3);
                
                metrics.add(Json.createObjectBuilder()
                    .add("hour", hour)
                    .add("efficiency", efficiency)
                    .add("timeBlock", String.format("%02d:00 - %02d:00", hour, (hour + 1) % 24))
                    .build());
            }
        }
        return metrics;
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
    // Add this method after analyzeEfficiencyPatterns
        private String createEfficiencyBar(double efficiency) {
            int barLength = 10;
            int filledBars = (int) (efficiency * barLength);
            StringBuilder bar = new StringBuilder();
            
            for (int i = 0; i < barLength; i++) {
                if (i < filledBars) {
                    bar.append("■");  // Using a different block character that's more compatible
                } else {
                    bar.append("□");  // Empty block character
                }
            }
            return bar.toString();
        }
}