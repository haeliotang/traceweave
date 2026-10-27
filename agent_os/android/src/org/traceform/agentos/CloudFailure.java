package org.traceform.agentos;

/** Display only locally classified errors, never the endpoint, credentials, or server body. */
final class CloudFailure {
    private CloudFailure() {}

    static String describe(Exception error, boolean en) {
        if (error instanceof java.net.SocketTimeoutException)
            return en ? "The request timed out. The provider may still have processed it." : "请求超时；服务商可能已经处理了请求。";
        if (error instanceof java.net.UnknownHostException || error instanceof java.net.ConnectException)
            return en ? "The endpoint could not be reached. Check the connection and settings." : "无法连接服务端，请检查网络和配置。";
        String message = error.getMessage() == null ? "" : error.getMessage();
        if (message.matches("Cloud model endpoint returned HTTP (401|403)"))
            return en ? "The provider rejected authentication or access." : "服务商拒绝了身份验证或访问。";
        if (message.equals("Cloud model endpoint returned HTTP 429"))
            return en ? "The provider reported a rate or quota limit." : "服务商报告请求频率或额度受限。";
        if (message.matches("Cloud model endpoint returned HTTP 5[0-9][0-9]"))
            return en ? "The provider reported a server error." : "服务商报告服务器错误。";
        if (message.matches("Cloud model endpoint returned HTTP 4[0-9][0-9]"))
            return en ? "The provider rejected the request; check its model and supported parameters."
                : "服务商拒绝了请求，请检查模型和支持的参数。";
        return en ? "The response was unavailable, invalid, or no longer authorized."
            : "响应不可用、格式不符合要求或授权已失效。";
    }
}
