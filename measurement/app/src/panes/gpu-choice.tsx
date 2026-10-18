import { useState } from "react";
import { GPU_LABEL, GPU_TYPES, type GpuType } from "../lib/cloud-control";
import { deploy, setGpuChoice, useDeploy } from "../lib/deploy-store";

/**
 * Which card the next deploy asks for, and — when a service already runs on the other one — the
 * redeploy that switches it. Switching replaces the revision in place with the same image, so it
 * costs a rollout (~1 min) and the next request's cold start, never a rebuild.
 */
export function GpuChoice() {
  const deployState = useDeploy();
  const [busy, setBusy] = useState(false);
  const running = deployState.status?.service.exists ? (deployState.status.service.gpuType ?? null) : null;
  const choice = deployState.gpuChoice;
  const mismatch = running !== null && running !== choice;
  const deploying = deployState.job?.status === "running";
  return (
    <>
      <div className="inspector-row control gpu-choice">
        <span className="k">GPU card</span>
        <select value={choice} disabled={deploying} onChange={(e) => setGpuChoice(e.target.value as GpuType)}>
          {GPU_TYPES.map((gpu) => (
            <option key={gpu} value={gpu}>
              {GPU_LABEL[gpu]}
            </option>
          ))}
        </select>
        <span className="v" title={running ? `The deployed service runs on ${running}` : "No service deployed"}>
          {running ? GPU_LABEL[running as GpuType] ?? running : "—"}
        </span>
      </div>
      {mismatch && (
        <div className="inspector-actions">
          <button
            disabled={busy || deploying}
            onClick={async () => {
              const name = deployState.status?.service.name ?? "the service";
              if (!window.confirm(`Redeploy ${name} on ${GPU_LABEL[choice]}? The next request pays a cold start on the new card.`)) return;
              setBusy(true);
              try {
                await deploy();
              } catch {
                // the store keeps the message
              } finally {
                setBusy(false);
              }
            }}
          >
            Switch to {GPU_LABEL[choice]}
          </button>
        </div>
      )}
    </>
  );
}
