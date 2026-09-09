// The automatic trigger, over Azure Queue Storage.
//
// The SDK is imported lazily so a `rabbitmq` deployment — the compose stack and every test —
// never pays for loading it, and a missing optional dependency fails with an instruction rather
// than at import time. Same arrangement as `../storage/s3.mjs`.
//
// This does what `rabbit.mjs` does: one segment at a time, retry what a retry could fix, announce
// the result. Azure Queue has none of the machinery it does that with, so four things changed:
//
//   * No exchange and no routing key. A queue here is a flat name, so the one exchange the Rabbit
//     path fans out from becomes two queues — one this worker drains, one it publishes to.
//   * No push delivery. Nothing calls us; we poll. A received message is invisible to other
//     readers for the visibility timeout instead of being held on a channel, and that timeout is
//     the deadline for the whole measurement rather than for an acknowledgement, which is why it
//     defaults to the measurement timeout and not to seconds.
//   * No ack, no nack, no delayed republish. Deleting the message is the ack. Leaving it is the
//     nack: Azure redelivers it once the visibility timeout expires, with `dequeueCount` one
//     higher. So the `x-measurement-attempt` header `rabbit.mjs` carries by hand is a counter the
//     service already keeps, and the growing `setTimeout` delay it schedules is the visibility
//     timeout instead. Both paths still give up after GREENV_MEASUREMENT_MAX_ATTEMPTS deliveries.
//   * A message that will never succeed goes to a poison queue rather than being dropped.
//     `rabbit.mjs` has nowhere to put one; worker 1's AzureQueueSegmentExtractionPollerAdapter
//     already settled the pattern for this transport, and a failed segment stays readable.

const MAX_ATTEMPTS = Number(process.env.GREENV_MEASUREMENT_MAX_ATTEMPTS ?? 3);

// Azure rejects a message larger than this, and the result envelope is the only body that could
// approach it: `positions` carries one record per sampled frame, up to the extractor's cap of 112.
// Checked here so an oversize packet is named in the log rather than surfacing as a 400 from the
// storage service. The envelope is NOT trimmed to fit — it is worker 2's published contract and
// the API reads the same bytes off both transports.
const MAX_MESSAGE_BYTES = 64 * 1024;

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms).unref?.());

async function sdk() {
  try {
    return await import("@azure/storage-queue");
  } catch {
    throw new Error(
      "the azure-queue adapter needs @azure/storage-queue — run `npm install` in " +
        "services/greenv-measurement-worker, or set GREENV_SEGMENT_QUEUE_ADAPTER=rabbitmq",
    );
  }
}

async function credential() {
  try {
    const { DefaultAzureCredential } = await import("@azure/identity");
    return new DefaultAzureCredential();
  } catch {
    throw new Error(
      "the azure-queue adapter needs @azure/identity when GREENV_AZURE_STORAGE_CONNECTION_STRING " +
        "is not set — run `npm install` in services/greenv-measurement-worker",
    );
  }
}

/**
 * One client per queue name, from a connection string or from a managed identity.
 *
 * The two credential shapes are the deployment's and the developer's: Container Apps assigns the
 * worker an identity and never holds an account key, while Azurite and a laptop have a connection
 * string and no identity to assume. Both Java services choose between exactly these two.
 */
async function queueClient(azure, name) {
  const { QueueClient } = await sdk();
  if (azure.connectionString) return new QueueClient(azure.connectionString, name);
  const endpoint = azure.endpoint.replace(/\/+$/, "");
  return new QueueClient(`${endpoint}/${name}`, await credential());
}

/**
 * The whole retry policy, in one place, so it can be read and tested without an Azure account.
 *
 * `dequeueCount` is 1 on the first delivery, where `rabbit.mjs`'s attempt header is 0 — the same
 * three deliveries counted from a different end.
 *
 * @returns {"delete"|"leave"|"poison"} delete: done. leave: let the visibility timeout redeliver
 *          it. poison: it will fail the same way for ever, so keep it where a human can read it.
 */
export function nextAction(error, dequeueCount) {
  if (!error) return "delete";
  return error.retryable === true && dequeueCount < MAX_ATTEMPTS ? "leave" : "poison";
}

export async function connectAzureQueue(config, { log = () => {} } = {}) {
  const azure = config.azure ?? {};
  if (!azure.queue || !azure.resultQueue) {
    throw new Error(
      "the azure-queue adapter needs GREENV_AZURE_MEASUREMENT_QUEUE_NAME and " +
        "GREENV_AZURE_MEASURED_QUEUE_NAME: Azure Queue has no exchange to fan one name out with",
    );
  }
  if (!azure.connectionString && !azure.endpoint) {
    throw new Error(
      "the azure-queue adapter needs GREENV_AZURE_QUEUE_ENDPOINT or " +
        "GREENV_AZURE_STORAGE_CONNECTION_STRING",
    );
  }

  const inbox = await queueClient(azure, azure.queue);
  const results = await queueClient(azure, azure.resultQueue);
  const poisonBox = azure.poisonQueue ? await queueClient(azure, azure.poisonQueue) : null;

  let draining = false;
  let loop = null;

  const poison = async (message, reason) => {
    // Deleting without keeping a copy would lose the segment silently, so the copy is written
    // first and the original removed only once it is somewhere else.
    try {
      if (poisonBox) await poisonBox.sendMessage(message.messageText);
      await inbox.deleteMessage(message.messageId, message.popReceipt);
      log({ event: "message-poisoned", reason, message: message.messageId, kept: Boolean(poisonBox) });
    } catch (error) {
      // Left visible again after the timeout. Better a duplicate delivery than a segment that
      // exists in neither queue.
      log({ event: "poison-failed", reason, message: message.messageId, error: error.message });
    }
  };

  const handleOne = async (message, handler) => {
    let request;
    try {
      request = JSON.parse(message.messageText);
    } catch (error) {
      log({ event: "message-rejected", reason: "unparseable", error: error.message });
      await poison(message, "unparseable");
      return;
    }
    try {
      await handler(request);
      await inbox.deleteMessage(message.messageId, message.popReceipt);
    } catch (error) {
      const attempt = message.dequeueCount ?? 1;
      const action = nextAction(error, attempt);
      log({ event: "message-failed", code: error.code ?? "unknown", attempt, action, error: error.message });
      if (action === "poison") await poison(message, error.code ?? "unknown");
      // "leave" is the whole of the retry: the message reappears when its visibility expires.
    }
  };

  return {
    async consume(handler) {
      draining = true;
      loop = (async () => {
        while (draining) {
          let received;
          try {
            const response = await inbox.receiveMessages({
              numberOfMessages: azure.maximumMessages,
              // Held for the length of a measurement, not of an acknowledgement. A timeout
              // shorter than the run redelivers the segment to a worker that is still measuring
              // it, and the GPU is paid for twice for one answer.
              visibilityTimeout: azure.visibilityTimeoutSeconds,
            });
            received = response.receivedMessageItems ?? [];
          } catch (error) {
            // A poll that fails is the transport being unreachable, which the next poll may well
            // survive. Unlike a lost AMQP connection there is no session to rebuild, so the
            // process stays up rather than exiting for a supervisor to restart.
            log({ event: "queue-poll-failed", error: error.message });
            await sleep(azure.pollDelayMs);
            continue;
          }
          if (received.length === 0) {
            await sleep(azure.pollDelayMs);
            continue;
          }
          for (const message of received) {
            if (!draining) break;
            await handleOne(message, handler);
          }
        }
      })();
      log({ event: "consuming", queue: azure.queue });
    },

    /** Announce a finished measurement so the API can move the segment on. */
    async publishResult(result) {
      const body = JSON.stringify(result);
      const bytes = Buffer.byteLength(body, "utf8");
      if (bytes > MAX_MESSAGE_BYTES) {
        throw new Error(
          `the measurement result for ${result.outputPrefix} is ${bytes} bytes and Azure Queue ` +
            `accepts ${MAX_MESSAGE_BYTES}; the packet itself is already in object storage`,
        );
      }
      await results.sendMessage(body);
    },

    async close() {
      draining = false;
      await loop?.catch(() => {});
    },
  };
}
