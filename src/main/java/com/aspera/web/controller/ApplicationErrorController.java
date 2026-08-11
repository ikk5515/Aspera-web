package com.aspera.web.controller;

import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.webmvc.error.ErrorController;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.servlet.ModelAndView;

@Controller
public class ApplicationErrorController implements ErrorController {

    @RequestMapping("/error")
    public Object handleError(HttpServletRequest request) {
        HttpStatus status = resolveStatus(request.getAttribute(RequestDispatcher.ERROR_STATUS_CODE));
        String requestPath = safeRequestPath(request.getAttribute(RequestDispatcher.ERROR_REQUEST_URI));
        ErrorDescription description = describe(status);

        if (isApiRequest(request, requestPath)) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status.value());
            body.put("error", description.title());
            body.put("message", description.message());
            return ResponseEntity.status(status)
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body);
        }

        ModelAndView view = new ModelAndView("error/error");
        view.setStatus(status);
        view.addObject("status", status.value());
        view.addObject("title", description.title());
        view.addObject("message", description.message());
        view.addObject("requestPath", requestPath);
        view.addObject("homePath", request.getUserPrincipal() == null ? "/login" : "/files");
        return view;
    }

    private HttpStatus resolveStatus(Object rawStatus) {
        if (rawStatus instanceof Integer statusCode) {
            HttpStatus status = HttpStatus.resolve(statusCode);
            if (status != null) {
                return status;
            }
        }
        return HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private boolean isApiRequest(HttpServletRequest request, String requestPath) {
        if (requestPath.startsWith("/admin/api/") || requestPath.startsWith("/api/")
                || requestPath.matches("^/admin/users/[^/]+/permissions/[^/]+$")
                || requestPath.equals("/files/dir-sizes") || requestPath.equals("/files/transfer-spec")) {
            return true;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(java.util.Locale.ROOT).contains(MediaType.APPLICATION_JSON_VALUE);
    }

    private String safeRequestPath(Object rawPath) {
        if (!(rawPath instanceof String path) || path.isBlank()) {
            return "/";
        }
        String safePath = path.replaceAll("[\\p{Cntrl}]", "");
        return safePath.length() > 512 ? safePath.substring(0, 512) : safePath;
    }

    private ErrorDescription describe(HttpStatus status) {
        return switch (status) {
            case BAD_REQUEST -> new ErrorDescription("Invalid request",
                    "Check the entered values and try again.");
            case FORBIDDEN -> new ErrorDescription("Access denied",
                    "Your account does not have permission to open this page.");
            case NOT_FOUND -> new ErrorDescription("Page not found",
                    "The requested page does not exist or may have moved.");
            case METHOD_NOT_ALLOWED -> new ErrorDescription("Method not allowed",
                    "This action is not available for the requested address.");
            case CONTENT_TOO_LARGE -> new ErrorDescription("Request too large",
                    "Reduce the request size and try again.");
            case TOO_MANY_REQUESTS -> new ErrorDescription("Too many sign-in attempts",
                    "Wait a few minutes before trying to sign in again.");
            case BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT -> new ErrorDescription("Service unavailable",
                    "The upstream service is temporarily unavailable. Please try again.");
            default -> new ErrorDescription("Something went wrong",
                    "The request could not be completed. Please try again later.");
        };
    }

    private record ErrorDescription(String title, String message) {
    }
}
