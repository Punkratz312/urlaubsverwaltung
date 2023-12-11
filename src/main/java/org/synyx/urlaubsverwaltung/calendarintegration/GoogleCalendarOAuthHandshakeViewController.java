package org.synyx.urlaubsverwaltung.calendarintegration;

import com.google.api.client.auth.oauth2.AuthorizationCodeRequestUrl;
import com.google.api.client.auth.oauth2.Credential;
import com.google.api.client.auth.oauth2.TokenResponse;
import com.google.api.client.googleapis.auth.oauth2.GoogleAuthorizationCodeFlow;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets;
import com.google.api.client.googleapis.auth.oauth2.GoogleClientSecrets.Details;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpResponse;
import com.google.api.client.http.HttpTransport;
import com.google.api.client.json.JsonFactory;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.Calendar;
import com.google.api.services.calendar.CalendarScopes;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.Collections;

import static java.lang.invoke.MethodHandles.lookup;
import static org.apache.http.HttpStatus.SC_OK;
import static org.slf4j.LoggerFactory.getLogger;
import static org.synyx.urlaubsverwaltung.security.SecurityRules.IS_OFFICE;

@Controller
@RequestMapping("/web")
public class GoogleCalendarOAuthHandshakeViewController {

    private static final Logger LOG = getLogger(lookup().lookupClass());
    private static final String APPLICATION_NAME = "Urlaubsverwaltung";
    private static final JsonFactory JSON_FACTORY = GsonFactory.getDefaultInstance();

    private static HttpTransport httpTransport;

    private final CalendarSettingsService calendarSettingsService;
    private final CalendarSyncService calendarSyncService;

    private GoogleAuthorizationCodeFlow flow;

    @Autowired
    GoogleCalendarOAuthHandshakeViewController(
        CalendarSettingsService calendarSettingsService,
        CalendarSyncService calendarSyncService
    ) throws GeneralSecurityException, IOException {

        this.calendarSettingsService = calendarSettingsService;
        this.calendarSyncService = calendarSyncService;
        httpTransport = GoogleNetHttpTransport.newTrustedTransport();
    }

    @PreAuthorize(IS_OFFICE)
    @GetMapping("/google-api-handshake")
    public String googleConnectionStatus(HttpServletRequest request) {
        return "redirect:" + authorize(request.getRequestURL().toString());
    }

    @PreAuthorize(IS_OFFICE)
    @GetMapping(value = "/google-api-handshake", params = "code")
    public String oauth2Callback(@RequestParam(value = "code") String code, HttpServletRequest request) {

        String redirectUrl = request.getRequestURL().toString();

        try {
            final TokenResponse response = flow.newTokenRequest(code).setRedirectUri(redirectUrl).execute();
            final Credential credential = flow.createAndStoreCredential(response, "userID");
            final Calendar client = new Calendar
                .Builder(httpTransport, JSON_FACTORY, credential)
                .setApplicationName(APPLICATION_NAME)
                .build();

            CalendarSettings calendarSettings = calendarSettingsService.getCalendarSettings();
            final HttpResponse httpResponse = checkGoogleCalendar(client, calendarSettings);

            if (httpResponse.getStatusCode() == SC_OK) {
                String refreshToken = credential.getRefreshToken();
                if (refreshToken == null) {
                    LOG.warn("OAuth Handshake was successful, but refresh token is null!");
                } else {
                    LOG.info("OAuth Handshake was successful!");
                }
                calendarSettings.getGoogleCalendarSettings().setRefreshToken(refreshToken);
                calendarSettingsService.save(calendarSettings);
                calendarSyncService.checkCalendarSyncSettings();
            } else {
                LOG.warn("OAuth handshake error {}", httpResponse.getStatusMessage());
            }

        } catch (IOException e) {
            LOG.error("Exception while handling OAuth2 callback ({}) Redirecting to google connection status page.", e.getMessage(), e);
        }

        return "redirect:/web/settings/calendar-sync";
    }

    private static HttpResponse checkGoogleCalendar(Calendar client, CalendarSettings calendarSettings) throws IOException {
        final String calendarId = calendarSettings.getGoogleCalendarSettings().getCalendarId();
        return client.calendars()
            .get(calendarId)
            .buildHttpRequestUsingHead().execute();
    }

    private String authorize(String redirectUri) {

        final GoogleCalendarSettings googleCalendarSettings = calendarSettingsService.getCalendarSettings().getGoogleCalendarSettings();

        final Details web = new Details();
        web.setClientId(googleCalendarSettings.getClientId());
        web.setClientSecret(googleCalendarSettings.getClientSecret());

        final GoogleClientSecrets clientSecrets = new GoogleClientSecrets();

        clientSecrets.setWeb(web);

        flow = new GoogleAuthorizationCodeFlow.Builder(httpTransport, JSON_FACTORY, clientSecrets,
            Collections.singleton(CalendarScopes.CALENDAR))
            .setApprovalPrompt("force")
            .setAccessType("offline")
            .build();

        final AuthorizationCodeRequestUrl authorizationUrl = flow.newAuthorizationUrl().setRedirectUri(redirectUri);
        LOG.info("using authorizationUrl {}", authorizationUrl);

        return authorizationUrl.build();
    }
}
