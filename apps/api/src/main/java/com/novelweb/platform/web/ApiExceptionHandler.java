package com.novelweb.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(NoResourceFoundException.class)
  public ResponseEntity<ProblemDetail> handleNotFound(
      NoResourceFoundException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.NOT_FOUND,
        "Not found",
        "NOT_FOUND",
        "The requested endpoint was not found.",
        correlationId(request));
  }

  @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
  public ResponseEntity<ProblemDetail> handleMethodNotAllowed(
      HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
    return problem(
        HttpStatus.METHOD_NOT_ALLOWED,
        "Method not allowed",
        "METHOD_NOT_ALLOWED",
        "The HTTP method is not available for this endpoint.",
        correlationId(request));
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpected(
      Exception exception, HttpServletRequest request) {
    String correlationId = correlationId(request);
    LOGGER.error(
        "Unhandled API exception; correlationId={}, exceptionType={}",
        correlationId,
        exception.getClass().getSimpleName());

    return problem(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "Internal server error",
        "INTERNAL_ERROR",
        "The request could not be completed.",
        correlationId);
  }

  private String correlationId(HttpServletRequest request) {
    Object value = request.getAttribute(CorrelationIdFilter.REQUEST_ATTRIBUTE);
    return value instanceof String id ? id : "unavailable";
  }

  private ResponseEntity<ProblemDetail> problem(
      HttpStatus status, String title, String code, String detail, String correlationId) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setTitle(title);
    problem.setProperty("code", code);
    problem.setProperty("correlationId", correlationId);
    return ResponseEntity.status(status)
        .cacheControl(CacheControl.noStore())
        .header(HttpHeaders.PRAGMA, "no-cache")
        .body(problem);
  }
}
