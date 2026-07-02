import { http, HttpResponse } from "msw";

/** @type {import("msw").RequestHandler[]} */
export const handlers = [
  http.get("/internal/release-center/applications", () => HttpResponse.json([])),
  http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
  http.get("/internal/release-center/plans", () => HttpResponse.json([])),
  http.get("/internal/release-center/servers", () => HttpResponse.json([])),
  http.get("/internal/release-center/script-profiles", () => HttpResponse.json([])),
];
