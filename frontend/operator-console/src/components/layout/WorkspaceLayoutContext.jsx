import { createContext, useContext } from "react";

const noop = () => {};

/**
 * @typedef {object} WorkspaceLayoutContextValue
 * @property {boolean} showMenuLabels
 * @property {(nextValue: boolean | ((current: boolean) => boolean)) => void} setShowMenuLabels
 * @property {boolean} isWorkspaceExpanded
 * @property {(nextValue: boolean | ((current: boolean) => boolean)) => void} setWorkspaceExpanded
 * @property {() => void} toggleMenuLabels
 * @property {() => void} toggleWorkspaceExpanded
 */

/** @type {WorkspaceLayoutContextValue} */
const defaultWorkspaceLayout = {
  showMenuLabels: true,
  setShowMenuLabels: noop,
  isWorkspaceExpanded: false,
  setWorkspaceExpanded: noop,
  toggleMenuLabels: noop,
  toggleWorkspaceExpanded: noop,
};

export const WorkspaceLayoutContext = createContext(defaultWorkspaceLayout);

export function useWorkspaceLayout() {
  return useContext(WorkspaceLayoutContext);
}
