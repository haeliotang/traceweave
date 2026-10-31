package org.traceform.agentos;

/** Display only locally classified errors, never the endpoint, credentials, or server body. */
final class CloudFailure {
    private CloudFailure() {}

    static String describe(Exception error, boolean en) {
        if (error instanceof java.net.SocketTimeoutException)
            return T.s("cloudfailure.the_request_timed_out_the_provider_may", en);
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException)
            return T.s("cloudfailure.the_endpoint_could_not_be_reached_chec", en);
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.matches("Cloud model endpoint returned HTTP (401|403)"))
            return T.s("cloudfailure.the_provider_rejected_authentication_o", en);
        if (message.equals("Cloud model endpoint returned HTTP 429"))
            return T.s("cloudfailure.the_provider_reported_a_rate_or_quota", en);
        if (message.matches("Cloud model endpoint returned HTTP 5[0-9][0-9]"))
            return T.s("cloudfailure.the_provider_reported_a_server_error", en);
        if (message.matches("Cloud model endpoint returned HTTP 4[0-9][0-9]"))
            return T.s("cloudfailure.the_provider_rejected_the_request_chec", en);
        return T.s("cloudfailure.the_response_was_unavailable_invalid_o", en);
    }
}
