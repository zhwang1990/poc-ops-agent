package com.company.opsagent.controlplane.bootstrap.api;

import com.company.opsagent.contracts.identity.IdentityErrorResponse;
import com.company.opsagent.controlplane.modules.identity.application.IdentityAuthenticationException;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchException;
import jakarta.validation.ConstraintViolationException;
import java.time.OffsetDateTime;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;
import org.springframework.web.server.ResponseStatusException;

/**
 * 控制面全局异常处理器。
 *
 * <p>该类负责把框架异常和业务骨架中的典型异常转换成统一错误结构，避免直接向调用方暴露框架细节。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(IdentityAuthenticationException.class)
  public ResponseEntity<IdentityErrorResponse> handleIdentityAuthentication(
      IdentityAuthenticationException exception,
      ServerWebExchange exchange) {
    return ResponseEntity.status(resolveIdentityStatus(exception.errorCode()))
        .body(new IdentityErrorResponse(
            exception.errorCode(),
            exception.getMessage(),
            traceId(exchange)));
  }

  /**
   * 处理显式的非法参数异常。
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ResponseEntity<ApiError> handleIllegalArgument(
      IllegalArgumentException exception,
      ServerWebExchange exchange) {
    return build(HttpStatus.BAD_REQUEST, "INVALID_ARGUMENT", exception.getMessage(), exchange);
  }

  @ExceptionHandler(SqlWorkbenchException.class)
  public ResponseEntity<ApiError> handleSqlWorkbench(
      SqlWorkbenchException exception,
      ServerWebExchange exchange) {
    return build(resolveSqlWorkbenchStatus(exception.code()), exception.code(), exception.getMessage(), exchange);
  }

  /**
   * 处理绑定、校验和输入解析相关异常。
   */
  @ExceptionHandler({
      BindException.class,
      WebExchangeBindException.class,
      ServerWebInputException.class,
      HandlerMethodValidationException.class,
      ConstraintViolationException.class
  })
  public ResponseEntity<ApiError> handleValidation(
      Exception exception,
      ServerWebExchange exchange) {
    return build(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "request validation failed", exchange);
  }

  /**
   * 保留下游边界已经分类过的 HTTP 状态，避免把 Worker 的 404/401 等状态折叠成 500。
   */
  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiError> handleResponseStatus(
      ResponseStatusException exception,
      ServerWebExchange exchange) {
    HttpStatus status = HttpStatus.resolve(exception.getStatusCode().value());
    HttpStatus resolvedStatus = status == null ? HttpStatus.INTERNAL_SERVER_ERROR : status;
    String code = switch (resolvedStatus) {
      case NOT_FOUND -> "NOT_FOUND";
      case UNAUTHORIZED -> "UNAUTHENTICATED";
      case FORBIDDEN -> "FORBIDDEN";
      case BAD_REQUEST -> "INVALID_ARGUMENT";
      default -> resolvedStatus.is5xxServerError() ? "INTERNAL_ERROR" : "REQUEST_FAILED";
    };
    String message = exception.getReason() == null ? resolvedStatus.getReasonPhrase() : exception.getReason();
    return build(resolvedStatus, code, message, exchange);
  }

  /**
   * 兜底处理所有未分类异常。
   */
  @ExceptionHandler(Exception.class)
  public ResponseEntity<ApiError> handleUnexpected(
      Exception exception,
      ServerWebExchange exchange) {
    return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "unexpected server error", exchange);
  }

  /**
   * 构造统一错误响应实体。
   */
  private ResponseEntity<ApiError> build(
      HttpStatus status,
      String code,
      String message,
      ServerWebExchange exchange) {
    ApiError error = new ApiError(
        code,
        message,
        exchange.getRequest().getPath().value(),
        exchange.getRequest().getId(),
        OffsetDateTime.now());
    return ResponseEntity.status(status).body(error);
  }

  private HttpStatus resolveIdentityStatus(String errorCode) {
    return switch (errorCode) {
      case "INVALID_CREDENTIALS", "SESSION_NOT_FOUND" -> HttpStatus.UNAUTHORIZED;
      case "ACCOUNT_LOCKED" -> HttpStatus.LOCKED;
      case "ACCOUNT_DISABLED" -> HttpStatus.FORBIDDEN;
      case "ACCOUNT_NOT_FOUND", "CREDENTIAL_NOT_FOUND" -> HttpStatus.NOT_FOUND;
      default -> HttpStatus.BAD_REQUEST;
    };
  }

  private HttpStatus resolveSqlWorkbenchStatus(String errorCode) {
    return switch (errorCode) {
      case "SQL_DML_WORKFLOW_REQUIRED" -> HttpStatus.CONFLICT;
      default -> HttpStatus.BAD_REQUEST;
    };
  }

  private String traceId(ServerWebExchange exchange) {
    String traceId = exchange.getRequest().getHeaders().getFirst("X-Trace-Id");
    return traceId == null || traceId.isBlank() ? exchange.getRequest().getId() : traceId;
  }
}
