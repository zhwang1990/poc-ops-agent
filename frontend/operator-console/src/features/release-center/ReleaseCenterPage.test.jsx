import { readFileSync } from "node:fs";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { server } from "../../test/server.js";
import { ReleaseCenterPage } from "./ReleaseCenterPage.jsx";

const releaseCenterCss = readFileSync("src/features/release-center/ReleaseCenterPage.module.css", "utf8");

describe("ReleaseCenterPage", () => {
  it("keeps Liberty script parameter remove buttons compact", () => {
    const rowRule = cssRule("scriptParameterRow");
    const removeButtonRule = cssRule("removeParameterButton");
    const actionsRule = cssRule("dialogActions");

    expect(rowRule).toContain("grid-template-columns: minmax(0, 2fr) minmax(0, 4fr) 88px");
    expect(rowRule).toContain("align-items: end");
    expect(removeButtonRule).toContain("height: 32px");
    expect(removeButtonRule).toContain("align-self: end");
    expect(removeButtonRule).toContain("margin-bottom: 10px");
    expect(removeButtonRule).not.toContain("align-self: stretch");
    expect(removeButtonRule).not.toContain("min-height: 52px");
    expect(actionsRule).toContain("padding-top: 2px");
  });

  it("keeps target environment buttons icon aligned", () => {
    const switchRule = cssRule("environmentSwitch");
    const buttonRule = cssRule("environmentButton");
    const iconRule = cssRule("environmentIcon");

    expect(switchRule).toContain("width: 186px");
    expect(buttonRule).toContain("display: inline-flex");
    expect(buttonRule).toContain("align-items: center");
    expect(buttonRule).toContain("gap: 5px");
    expect(iconRule).toContain("flex: 0 0 auto");
    expect(iconRule).toContain("width: 13px");
  });

  it("renders icons in the target environment switch", async () => {
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    await screen.findByText("orders");
    for (const label of ["DEV", "SIT", "UAT"]) {
      const environmentButton = screen.getByRole("button", { name: label });
      expect(environmentButton).toBeInTheDocument();
      expect(environmentButton.querySelector("svg")).toBeInTheDocument();
    }
  });

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

  it("creates a dev release plan from registered catalog data", async () => {
    let createCalled = false;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.post("/internal/release-center/plans", async () => {
        createCalled = true;
        return HttpResponse.json(releasePlan);
      }),
    );

    renderReleaseCenter();

    const createButton = await screen.findByRole("button", { name: "新建发布单" });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);
    await userEvent.click(await screen.findByRole("button", { name: "创建发布单" }));

    expect(createCalled).toBe(true);
    expect(await screen.findByText("rel-1")).toBeInTheDocument();
  });

  it("opens the release plan form even when artifacts are missing", async () => {
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    const createButton = await screen.findByRole("button", { name: "新建发布单" });
    expect(createButton).toBeEnabled();
    await userEvent.click(createButton);

    const dialog = await screen.findByRole("dialog", { name: "新建发布单" });
    expect(within(dialog).getAllByText("缺少可发布制品").length).toBeGreaterThan(0);
    expect(within(dialog).getByRole("button", { name: "创建发布单" })).toBeDisabled();
  });

  it("does not report missing artifacts before release nodes determine the mode", async () => {
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("button", { name: "新建发布单" }));

    const dialog = await screen.findByRole("dialog", { name: "新建发布单" });
    expect(within(dialog).queryByText("缺少可发布制品")).not.toBeInTheDocument();
    expect(within(dialog).getByText("等待发布节点确定发布方式")).toBeInTheDocument();
    expect(within(dialog).getAllByText("缺少可用发布节点").length).toBeGreaterThan(0);
    expect(within(dialog).getByRole("button", { name: "创建发布单" })).toBeDisabled();
  });

  it("creates a Liberty script release plan without artifacts", async () => {
    /** @type {unknown} */
    let savedRequest = null;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseLibertyScriptServer])),
      http.post("/internal/release-center/plans", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedRequest = requestBody;
        return HttpResponse.json({
          ...releasePlan,
          artifactId: null,
          nodes: [
            {
              nodeId: "liberty-script-1",
              serverType: "LIBERTY",
              managementMode: "LIBERTY_SCRIPT_PROFILE",
              sequence: 1,
              status: "PENDING",
            },
          ],
        });
      }),
    );

    renderReleaseCenter();

    const actionButtons = (await screen.findAllByRole("button"))
      .filter((button) => String(button.className).includes("actionButton"));
    const createButton = actionButtons[1];
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("\\\\jenkins\\share\\orders\\latest\\orders.war")).toBeInTheDocument();
    const dialogButtons = within(dialog).getAllByRole("button");
    await userEvent.click(dialogButtons[dialogButtons.length - 1]);

    await waitFor(() =>
      expect(savedRequest).toEqual({
        applicationId: "orders",
        targetEnvironment: "dev",
        nodeIds: ["liberty-script-1"],
      }),
    );
  });

  it("requires a shared artifact path for Liberty script releases", async () => {
    const serverWithoutArtifactPath = {
      ...releaseLibertyScriptServer,
      scriptProfile: {
        profileId: "liberty-war-deploy",
        parameters: [
          { name: "serverName", value: "defaultServer" },
          { name: "applicationName", value: "orders" },
        ],
      },
    };
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([serverWithoutArtifactPath])),
    );

    renderReleaseCenter();

    const actionButtons = (await screen.findAllByRole("button"))
      .filter((button) => String(button.className).includes("actionButton"));
    const createButton = actionButtons[1];
    await userEvent.click(createButton);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByText("缺少 Liberty 制品共享路径").length).toBeGreaterThan(0);
    expect(within(dialog).getAllByRole("button").at(-1)).toBeDisabled();
  });

  it("executes a draft release plan from the plan list", async () => {
    let executeCalled = false;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.post("/internal/release-center/plans/rel-1/execute", async () => {
        executeCalled = true;
        return HttpResponse.json({ ...releasePlan, status: "PARTIAL_FAILED" });
      }),
    );

    renderReleaseCenter();

    const executeButton = await screen.findByRole("button", { name: "执行" });
    expect(executeButton).toBeEnabled();
    await userEvent.click(executeButton);

    expect(executeCalled).toBe(true);
    expect(await screen.findByText("PARTIAL_FAILED")).toBeInTheDocument();
  });

  it("registers a Tomcat server from the servers tab", async () => {
    /** @type {unknown} */
    let savedServer = null;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
      http.post("/internal/release-center/servers", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedServer = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Add release server" }));

    const dialog = await screen.findByRole("dialog", { name: "新增服务器" });
    await userEvent.type(within(dialog).getByLabelText("Node ID"), "tomcat-dev-1");
    await userEvent.clear(within(dialog).getByLabelText("Management endpoint"));
    await userEvent.type(within(dialog).getByLabelText("Management endpoint"), "http://127.0.0.1:18080/manager/text");
    await userEvent.clear(within(dialog).getByLabelText("Application path"));
    await userEvent.type(within(dialog).getByLabelText("Application path"), "/orders-demo");
    await userEvent.type(within(dialog).getByLabelText("Credential alias"), "tomcat-dev");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save release server" }));

    await waitFor(() =>
      expect(savedServer).toEqual({
        nodeId: "tomcat-dev-1",
        targetEnvironment: "dev",
        serverType: "TOMCAT",
        managementMode: "TOMCAT_WAR_UPLOAD",
        managementEndpoint: "http://127.0.0.1:18080/manager/text",
        applicationPath: "/orders-demo",
        credentialAlias: "tomcat-dev",
        enabled: true,
      }),
    );
    expect((await screen.findAllByText("tomcat-dev-1")).length).toBeGreaterThan(0);
  });

  it("registers a Liberty script profile server from the servers tab", async () => {
    /** @type {unknown} */
    let savedServer = null;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
      http.post("/internal/release-center/servers", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedServer = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    const tablist = await screen.findByRole("tablist");
    await userEvent.click(within(tablist).getAllByRole("tab")[3]);
    await userEvent.click(await screen.findByRole("button", { name: "Add release server" }));

    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Node ID"), "liberty-dev-1");
    await userEvent.selectOptions(within(dialog).getByLabelText("Server type"), "LIBERTY");
    await userEvent.clear(within(dialog).getByLabelText("Management endpoint"));
    await userEvent.type(within(dialog).getByLabelText("Management endpoint"), "https://liberty-dev.example");
    await userEvent.clear(within(dialog).getByLabelText("Application path"));
    await userEvent.type(within(dialog).getByLabelText("Application path"), "/orders");
    expect(within(dialog).getByLabelText("Script Profile ID")).toHaveValue("liberty-war-deploy");
    expect(within(dialog).getByLabelText("Parameter 1 name")).toHaveValue("serverName");
    expect(within(dialog).getByLabelText("Parameter 1 value")).toHaveValue("defaultServer");
    expect(within(dialog).getByLabelText("Parameter 3 name")).toHaveValue("artifactPath");
    expect(within(dialog).getByLabelText("Parameter 3 value")).toHaveValue(
      "\\\\jenkins\\share\\orders\\latest\\orders.war",
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save release server" }));

    await waitFor(() =>
      expect(savedServer).toEqual({
        nodeId: "liberty-dev-1",
        targetEnvironment: "dev",
        serverType: "LIBERTY",
        managementMode: "LIBERTY_SCRIPT_PROFILE",
        managementEndpoint: "https://liberty-dev.example",
        applicationPath: "/orders",
        credentialAlias: null,
        scriptProfile: {
          profileId: "liberty-war-deploy",
          parameters: [
            { name: "serverName", value: "defaultServer" },
            { name: "applicationName", value: "orders" },
            { name: "artifactPath", value: "\\\\jenkins\\share\\orders\\latest\\orders.war" },
          ],
        },
        enabled: true,
      }),
    );
    expect(await screen.findByText("\\\\jenkins\\share\\orders\\latest\\orders.war")).toBeInTheDocument();
    expect(screen.getByText("\\\\jenkins\\share\\orders\\latest\\orders.war").closest("a")).toBeNull();
    expect(await screen.findByRole("button", { name: "Copy artifact path for liberty-dev-1" })).toBeInTheDocument();
  });

  it("edits a release server from the servers tab", async () => {
    /** @type {unknown} */
    let savedServer = null;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.post("/internal/release-center/servers", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedServer = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Edit node-1" }));

    const dialog = await screen.findByRole("dialog", { name: "编辑服务器" });
    expect(within(dialog).getByLabelText("Node ID")).toHaveAttribute("readonly");
    await userEvent.clear(within(dialog).getByLabelText("Application path"));
    await userEvent.type(within(dialog).getByLabelText("Application path"), "/orders-edit");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save release server" }));

    await waitFor(() =>
      expect(savedServer).toEqual({
        ...releaseServer,
        applicationPath: "/orders-edit",
      }),
    );
    expect(await screen.findByText("/orders-edit")).toBeInTheDocument();
  });

  it("deletes a release server from the servers tab", async () => {
    /** @type {string | null} */
    let deletedNodeId = null;
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
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.delete("/internal/release-center/servers/:nodeId", ({ params }) => {
        deletedNodeId = String(params.nodeId);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Delete node-1" }));
    await userEvent.click(await screen.findByRole("button", { name: "Confirm delete node-1" }));

    await waitFor(() => expect(deletedNodeId).toBe("node-1"));
    await waitFor(() => expect(screen.queryByText("node-1")).not.toBeInTheDocument());
  });
});

function renderReleaseCenter() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <ReleaseCenterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * @param {string} className
 */
function cssRule(className) {
  const escapedClassName = className.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = new RegExp(`\\.${escapedClassName}\\s*\\{([^}]*)\\}`).exec(releaseCenterCss);
  return match?.[1] ?? "";
}

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

const releaseLibertyScriptServer = {
  nodeId: "liberty-script-1",
  targetEnvironment: "dev",
  serverType: "LIBERTY",
  managementMode: "LIBERTY_SCRIPT_PROFILE",
  managementEndpoint: "https://liberty-dev.example",
  applicationPath: "/orders",
  credentialAlias: null,
  scriptProfile: {
    profileId: "liberty-war-deploy",
    parameters: [
      { name: "serverName", value: "defaultServer" },
      { name: "applicationName", value: "orders" },
      { name: "artifactPath", value: "\\\\jenkins\\share\\orders\\latest\\orders.war" },
    ],
  },
  enabled: true,
};

const releaseArtifact = {
  artifactId: "artifact-1",
  applicationId: "orders",
  targetEnvironment: "dev",
  artifactType: "WAR",
  checksum: "sha256:abc123",
  originalFilename: "orders.war",
  storageKey: "artifact-1.war",
  byteSize: 3,
  uploadedBy: "operator-1",
  sourceType: "OPERATOR_UPLOAD",
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
