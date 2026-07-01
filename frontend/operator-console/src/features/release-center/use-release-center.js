import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";

import {
  confirmReleasePlan,
  createReleasePlan,
  executeReleasePlan,
  listReleaseApplications,
  listReleasePlans,
  listReleaseServers,
  rotateReleaseCredential,
  saveReleaseApplication,
  saveReleaseServer,
  testReleaseServer,
  uploadTomcatWar,
} from "../../api/release-center-api.js";

const RELEASE_APPLICATIONS_QUERY_KEY = ["release-center", "applications"];
const RELEASE_PLANS_QUERY_KEY = ["release-center", "plans"];
const RELEASE_SERVERS_QUERY_KEY = ["release-center", "servers"];

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
export function useReleaseServers(targetEnvironment) {
  return useQuery({
    queryKey: [...RELEASE_SERVERS_QUERY_KEY, targetEnvironment],
    queryFn: () => listReleaseServers(targetEnvironment),
    enabled: Boolean(targetEnvironment),
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
    onSuccess: () => queryClient.invalidateQueries({ queryKey: RELEASE_SERVERS_QUERY_KEY }),
  });
}

export function useUploadTomcatWar() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: uploadTomcatWar,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: RELEASE_PLANS_QUERY_KEY }),
  });
}

export function useCreateReleasePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: createReleasePlan,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: RELEASE_PLANS_QUERY_KEY }),
  });
}

export function useConfirmReleasePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: confirmReleasePlan,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: RELEASE_PLANS_QUERY_KEY }),
  });
}

export function useExecuteReleasePlan() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: executeReleasePlan,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: RELEASE_PLANS_QUERY_KEY }),
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
