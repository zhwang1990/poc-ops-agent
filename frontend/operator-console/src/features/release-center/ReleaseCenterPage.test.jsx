import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, within } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { server } from "../../test/server.js";
import { ReleaseCenterPage } from "./ReleaseCenterPage.jsx";

describe("ReleaseCenterPage", () => {
  it("renders the release center workspace tabs", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <ReleaseCenterPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole("heading", { name: "发布中心" })).toBeInTheDocument();
    const tabs = screen.getByRole("tablist", { name: "发布中心配置" });
    for (const label of ["发布单", "制品", "应用", "服务器", "策略", "凭据"]) {
      expect(within(tabs).getByRole("tab", { name: label })).toBeInTheDocument();
    }
    expect(await screen.findByText("orders")).toBeInTheDocument();
    expect(await screen.findByText("node-1")).toBeInTheDocument();
  });
});

const releaseApplication = {
  applicationId: "orders",
  displayName: "订单服务",
  artifactType: "WAR",
  healthCheckPath: "/health",
  enabled: true,
};

const releaseServer = {
  nodeId: "node-1",
  targetEnvironment: "dev",
  serverType: "TOMCAT",
  managementMode: "TOMCAT_WAR_UPLOAD",
  managementEndpoint: "https://tomcat-dev.example",
  applicationPath: "/orders",
  credentialAlias: "tomcat-dev",
  enabled: true,
};

const releasePlan = {
  releaseId: "rel-1",
  applicationId: "orders",
  targetEnvironment: "dev",
  artifactId: "artifact-1",
  status: "DRAFT",
  parametersHash: "sha256:abc123",
  nodes: [
    {
      nodeId: "node-1",
      serverType: "TOMCAT",
      managementMode: "TOMCAT_WAR_UPLOAD",
      sequence: 1,
      status: "PENDING",
    },
  ],
};
