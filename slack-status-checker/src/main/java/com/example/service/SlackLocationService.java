package com.example.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.users.UsersListResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class SlackLocationService {
    
    @ConfigProperty(name = "slack.token")
    String slackToken;
    
    @ConfigProperty(name = "slack.user.id")
    String userId;

    private final Slack slack = Slack.getInstance();

    public UserLocation getUserLocation() {
        try {
            MethodsClient methods = slack.methods(slackToken);
            UsersListResponse response = methods.usersList(r -> r.token(slackToken));

            if (!response.isOk()) {
                System.err.println("Slack API error: " + response.getError());
                return new UserLocation("Error", "Error", 0, "");
            }

            return response.getMembers().stream()
                .filter(member -> member.getId().equals(userId))
                .findFirst()
                .map(member -> {
                    try {
                        return new UserLocation(
                            member.getTz() != null ? member.getTz() : "Unknown",
                            member.getTzLabel() != null ? member.getTzLabel() : "Unknown",
                            member.getTzOffset(),
                            member.getProfile() != null ? member.getProfile().getStatusText() : ""
                        );
                    } catch (Exception e) {
                        System.err.println("Error mapping user data: " + e.getMessage());
                        return new UserLocation("Error", "Error", 0, "");
                    }
                })
                .orElse(new UserLocation("Unknown", "Unknown", 0, ""));
        } catch (Exception e) {
            System.err.println("Error getting user location: " + e.getMessage());
            e.printStackTrace();
            return new UserLocation("Error", "Error", 0, "");
        }
    }

    public static class UserLocation {
        private final String timezone;
        private final String timezoneLabel;
        private final int timezoneOffset;
        private final String statusText;

        public UserLocation(String timezone, String timezoneLabel, int timezoneOffset, String statusText) {
            this.timezone = timezone;
            this.timezoneLabel = timezoneLabel;
            this.timezoneOffset = timezoneOffset;
            this.statusText = statusText;
        }

        // Getters
        public String getTimezone() { return timezone; }
        public String getTimezoneLabel() { return timezoneLabel; }
        public int getTimezoneOffset() { return timezoneOffset; }
        public String getStatusText() { return statusText; }
    }
}