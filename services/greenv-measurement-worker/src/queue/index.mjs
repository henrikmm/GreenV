import { connectRabbitQueue } from "./rabbit.mjs";
import { connectAzureQueue } from "./azure.mjs";

export function connectQueue(config, options) {
  return config.adapter === "azure-queue"
    ? connectAzureQueue(config, options)
    : connectRabbitQueue(config, options);
}
