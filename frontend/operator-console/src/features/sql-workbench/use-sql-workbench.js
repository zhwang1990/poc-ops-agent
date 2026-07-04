import { useMutation, useQuery } from "@tanstack/react-query";

import {
  createSqlConnection,
  askSqlAssistant,
  deleteSqlConnection,
  listSqlConnections,
  readSqlMetadata,
  probeSqlConnection,
  readSqlResultPage,
  runReadOnlySqlQuery,
  updateSqlConnection,
  validateSqlQuery,
} from "../../api/sql-api.js";

export function useSqlConnections() {
  return useQuery({
    queryKey: ["sql-workbench", "connections"],
    queryFn: listSqlConnections,
  });
}

export function useCreateSqlConnection() {
  return useMutation({
    mutationFn: createSqlConnection,
  });
}

export function useUpdateSqlConnection() {
  return useMutation({
    /**
     * @param {{
     *   connectionId: string,
     *   request: import("../../schemas/sql-schemas.js").SqlConnectionUpdateRequest,
     * }} input
     */
    mutationFn: (input) => updateSqlConnection(input.connectionId, input.request),
  });
}

export function useDeleteSqlConnection() {
  return useMutation({
    mutationFn: deleteSqlConnection,
  });
}

export function useProbeSqlConnection() {
  return useMutation({
    mutationFn: probeSqlConnection,
  });
}

export function useValidateSqlQuery() {
  return useMutation({
    mutationFn: validateSqlQuery,
  });
}

export function useRunReadOnlySqlQuery() {
  return useMutation({
    mutationFn: runReadOnlySqlQuery,
  });
}

export function useSqlAssistant() {
  return useMutation({
    mutationFn: askSqlAssistant,
  });
}

export function useRunSqlCompare() {
  return useMutation({
    /**
     * @param {{
     *   baseRequest: import("../../schemas/sql-schemas.js").SqlQueryRunRequest,
     *   compareRequest: import("../../schemas/sql-schemas.js").SqlQueryRunRequest,
     * }} input
     */
    mutationFn: async (input) => {
      const baseExecution = await runReadOnlySqlQuery(input.baseRequest);
      const compareExecution = await runReadOnlySqlQuery(input.compareRequest);
      const [basePage, comparePage] = await Promise.all([
        baseExecution.resultId
          ? readSqlResultPage({ resultId: baseExecution.resultId })
          : Promise.resolve(null),
        compareExecution.resultId
          ? readSqlResultPage({ resultId: compareExecution.resultId })
          : Promise.resolve(null),
      ]);
      return {
        baseExecution,
        basePage,
        baseRequest: input.baseRequest,
        compareExecution,
        comparePage,
        compareRequest: input.compareRequest,
      };
    },
  });
}

/**
 * @param {string | null | undefined} resultId
 * @param {string | null | undefined} pageToken
 */
export function useSqlResultPage(resultId, pageToken = null) {
  return useQuery({
    enabled: Boolean(resultId),
    queryKey: ["sql-workbench", "results", resultId, pageToken ?? ""],
    queryFn: () => readSqlResultPage({ resultId: String(resultId), pageToken }),
  });
}

/**
 * @param {string | null | undefined} connectionId
 * @param {string | null | undefined} schema
 * @param {boolean} enabled
 */
export function useSqlMetadata(connectionId, schema, enabled) {
  return useQuery({
    enabled: Boolean(enabled && connectionId && schema),
    queryKey: ["sql-workbench", "metadata", connectionId ?? "", schema ?? ""],
    queryFn: () => readSqlMetadata({ connectionId: String(connectionId), schema: String(schema) }),
    staleTime: 60_000,
  });
}
