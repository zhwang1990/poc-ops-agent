import { useState } from "react";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { DataTable } from "./DataTable.jsx";

/**
 * @typedef {{
 *   align?: "left" | "center" | "right",
 *   header: string,
 *   key: string,
 *   render: (row: unknown) => string,
 * }} TestColumn
 */

const rows = [
  { id: "node-a", status: "healthy", latency: "12ms" },
  { id: "node-b", status: "warning", latency: "48ms" },
  { id: "node-c", status: "healthy", latency: "16ms" },
];

const columnKeys = {
  latency: "latency",
  nodeId: "id",
  status: "status",
};

/** @type {TestColumn[]} */
const columns = [
  { header: "节点", key: columnKeys.nodeId, render: (row) => getCell(row, "id") },
  { header: "状态", key: columnKeys.status, render: (row) => getCell(row, "status") },
  {
    align: "right",
    header: "延迟",
    key: columnKeys.latency,
    render: (row) => getCell(row, "latency"),
  },
];

describe("DataTable", () => {
  it("renders a labelled table with columns and rows", () => {
    render(
      <DataTable
        ariaLabel="节点健康表"
        columns={columns}
        getRowKey={(row) => getCell(row, "id")}
        rows={rows.slice(0, 2)}
      />,
    );

    const table = screen.getByRole("table", { name: "节点健康表" });
    expect(within(table).getByRole("columnheader", { name: "节点" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "延迟" })).toHaveAttribute(
      "data-align",
      "right",
    );
    expect(within(table).getByText("node-a")).toBeInTheDocument();
    expect(within(table).getByText("48ms")).toHaveAttribute("data-align", "right");
  });

  it("uses shared feedback semantics for empty data", () => {
    render(
      <DataTable
        ariaLabel="节点健康表"
        columns={columns}
        emptyMessage="当前筛选条件下没有节点。"
        emptyTitle="没有节点"
        rows={[]}
      />,
    );

    expect(screen.getByRole("status", { name: "没有节点" })).toHaveTextContent(
      "当前筛选条件下没有节点。",
    );
    expect(screen.queryByRole("table", { name: "节点健康表" })).not.toBeInTheDocument();
  });

  it("supports controlled client-side pagination", async () => {
    const user = userEvent.setup();
    render(<PaginatedTable />);

    expect(screen.getByText("node-a")).toBeInTheDocument();
    expect(screen.queryByText("node-c")).not.toBeInTheDocument();
    expect(screen.getByText("第 1 / 2 页，共 3 条")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "下一页" }));

    expect(screen.queryByText("node-a")).not.toBeInTheDocument();
    expect(screen.getByText("node-c")).toBeInTheDocument();
    expect(screen.getByText("第 2 / 2 页，共 3 条")).toBeInTheDocument();
  });
});

function PaginatedTable() {
  const [page, setPage] = useState(1);
  return (
    <DataTable
      ariaLabel="节点健康表"
      columns={columns}
      getRowKey={(row) => getCell(row, "id")}
      pagination={{
        page,
        pageSize: 2,
        onPageChange: setPage,
      }}
      rows={rows}
    />
  );
}

/**
 * @param {unknown} row
 * @param {string} key
 */
function getCell(row, key) {
  if (!row || typeof row !== "object" || !(key in row)) {
    return "";
  }
  const value = /** @type {Record<string, unknown>} */ (row)[key];
  return typeof value === "string" ? value : "";
}
