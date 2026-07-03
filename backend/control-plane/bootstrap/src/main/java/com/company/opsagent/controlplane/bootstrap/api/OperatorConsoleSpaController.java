package com.company.opsagent.controlplane.bootstrap.api;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 操作台前端浏览器路由入口。
 *
 * <p>发布包把 React 单页应用放在 classpath static 目录中。用户直接访问或刷新
 * `/login`、`/overview` 等前端路由时，控制面必须返回同一个 index.html，由前端
 * Router 继续处理；`/internal/**`、`/auth/**` 和 `/api/**` 仍由各自控制器处理。
 */
@RestController
public class OperatorConsoleSpaController {

  private final Resource indexHtml = new ClassPathResource("static/index.html");

  @GetMapping(
      value = {
          "/",
          "/login",
          "/overview",
          "/agent",
          "/rag",
          "/workflow-events",
          "/audit",
          "/help",
          "/skills",
          "/meeting-notes",
          "/meeting-notes/record/new",
          "/meeting-notes/recording-settings",
          "/meeting-notes/{noteId}",
          "/meeting-notes/{noteId}/edit",
          "/as400-ddl",
          "/quick-links",
          "/sql",
          "/model-settings",
          "/release"
      },
      produces = MediaType.TEXT_HTML_VALUE)
  public ResponseEntity<Resource> index() {
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noCache())
        .contentType(MediaType.TEXT_HTML)
        .body(indexHtml);
  }
}
