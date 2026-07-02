import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  confirmReleasePlan,
  createReleasePlan,
  deleteReleaseScriptProfile,
  deleteReleaseServer,
  executeReleasePlan,
  listReleaseApplications,
  listReleaseArtifacts,
  listReleasePlans,
  listReleaseScriptProfiles,
  listReleaseServers,
  rotateReleaseCredential,
  saveReleaseApplication,
  saveReleaseScriptProfile,
  saveReleaseServer,
  testReleaseServer,
  uploadTomcatWar,
} from "../../api/release-center-api.js";

const RELEASE_APPLICATIONS_QUERY_KEY = ["release-center", "applications"];
const RELEASE_ARTIFACTS_QUERY_KEY = ["release-center", "artifacts"];
const RELEASE_PLANS_QUERY_KEY = ["release-center", "plans"];
const RELEASE_SERVERS_QUERY_KEY = ["release-center", "servers"];
const RELEASE_SCRIPT_PROFILES_QUERY_KEY = ["release-center", "script-profiles"];

export function useReleaseApplications() {
  return useQuery({
    queryKey: RELEASE_APPLICATIONS_QUERY_KEY,
    queryFn: listReleaseApplications,
    staleTime: 15_000,
    retry: false,
  });
}

export function useReleasePlans() {
  return useQuery({
    queryKey: RELEASE_PLANS_QUERY_KEY,
    queryFn: listReleasePlans,
    staleTime: 10_000,
    retry: false,
  });
}

/**
 * @param {string} targetEnvironment
 */
export function useReleaseArtifacts(targetEnvironment) {
  return useQuery({
    queryKey: [...RELEASE_ARTIFACTS_QUERY_KEY, targetEnvironment],
    queryFn: () => listReleaseArtifacts(targetEnvironment),
    enabled: Boolean(targetEnvironment),
    staleTime: 15_000,
    retry: false,
  });
}

/**
 * @param {string} targetEnvironment
 */
export function useReleaseServers(targetEnvironment) {
  return useQuery({
    queryKey: [...RELEASE_SERVERS_QUERY_KEY, targetEnvironment],
    queryFn: () => listReleaseServers(targetEnvironment),
    enabled: Boolean(targetEnvironment),
    staleTime: 15_000,
    retry: false,
  });
}

export function useReleaseScriptProfiles() {
  return useQuery({
    queryKey: RELEASE_SCRIPT_PROFILES_QUERY_KEY,
    queryFn: listReleaseScriptProfiles,
    staleTime: 15_000,
    retry: false,
  });
}

export function useSaveReleaseApplication() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: saveReleaseApplication,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: RELEASE_APPLICATIONS_QUERY_KEY }),
  });
}

export function useSaveReleaseServer() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: saveReleaseServer,
    onSuccess: (server) => {
      queryClient.setQueryData(
        [...RELEASE_SERVERS_QUERY_KEY, server.targetEnvironment],
        /**
         * @param {unknown} current
         */
        (current) => upsertById(Array.isArray(current) ? current : [], server, "nodeId"),
      );
    },
  });
}

export function useDeleteReleaseServer() {
  const queryClient = useQueryClient();
  return useMutation({
    /**
     * @param {{nodeId: string, targetEnvironment: string}} variables
     */
    mutationFn: (variables) => deleteReleaseServer(variables.nodeId),
    onSuccess: (_result, variables) => {
      queryClient.setQueryData(
        [...RELEASE_SERVERS_QUERY_KEY, variables.targetEnvironment],
        /**
         * @param {unknown} current
         */
        (current) => removeById(Array.isArray(current) ? current : [], variables.nodeId, "nodeId"),
      );
    },
  });
}

export function useSaveReleaseScriptProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: saveReleaseScriptProfile,
    onSuccess: (profile) => {
      queryClient.setQueryData(
        RELEASE_SCRIPT_PROFILES_QUERY_KEY,
        /**
         * @param {unknown} current
         */
        (current) => upsertById(Array.isArray(current) ? current : [], profile, "profileId"),
      );
    },
  });
}

export function useDeleteReleaseScriptProfile() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: deleteReleaseScriptProfile,
    onSuccess: (_result, variables) => {
      queryClient.setQueryData(
        RELEASE_SCRIPT_PROFILES_QUERY_KEY,
        /**
         * @param {unknown} current
         */
        (current) => removeById(Array.isArray(current) ? current : [], variables.profileId, "profileId"),
      );
    },
  });
}

export function useUploadTomcatWar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: uploadTomcatWar,
    onSuccess: (artifact) => {
      queryClient.setQueryData(
        [...RELEASE_ARTIFACTS_QUERY_KEY, artifact.targetEnvironment],
        /**
         * @param {unknown} current
         */
        (current) => upsertById(Array.isArray(current) ? current : [], artifact, "artifactId"),
      );
    },
  });
}

export function useCreateReleasePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createReleasePlan,
    onSuccess: (plan) => {
      queryClient.setQueryData(RELEASE_PLANS_QUERY_KEY, /**
       * @param {unknown} current
       */
      (current) => upsertById(Array.isArray(current) ? current : [], plan, "releaseId"));
    },
  });
}

export function useConfirmReleasePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: confirmReleasePlan,
    onSuccess: (plan) => {
      queryClient.setQueryData(RELEASE_PLANS_QUERY_KEY, /**
       * @param {unknown} current
       */
      (current) => upsertById(Array.isArray(current) ? current : [], plan, "releaseId"));
    },
  });
}

export function useExecuteReleasePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: executeReleasePlan,
    onSuccess: (plan) => {
      queryClient.setQueryData(RELEASE_PLANS_QUERY_KEY, /**
       * @param {unknown} current
       */
      (current) => upsertById(Array.isArray(current) ? current : [], plan, "releaseId"));
    },
  });
}

export function useRotateReleaseCredential() {
  return useMutation({
    mutationFn: rotateReleaseCredential,
  });
}

export function useTestReleaseServer() {
  return useMutation({
    mutationFn: testReleaseServer,
  });
}

/**
 * @template T
 * @param {T[]} items
 * @param {T} next
 * @param {keyof T} key
 * @returns {T[]}
 */
function upsertById(items, next, key) {
  const index = items.findIndex((item) => item[key] === next[key]);
  if (index === -1) {
    return [next, ...items];
  }
  return items.map((item, currentIndex) => (currentIndex === index ? next : item));
}

/**
 * @template T
 * @param {T[]} items
 * @param {unknown} id
 * @param {keyof T} key
 * @returns {T[]}
 */
function removeById(items, id, key) {
  return items.filter((item) => item[key] !== id);
}
