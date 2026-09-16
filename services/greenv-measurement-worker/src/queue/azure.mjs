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
//     defaults to the measurement timeout and not to seconds. A segment is now as many
//     reconstructions as it has 25 m windows, so the lease is renewed while the handler runs -
//     see `leaseRenewal` - and the timeout bounds one window rather than the whole segment.
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

/**
 * Keeps one message invisible for as long as the handler is still measuring it.
 *
 * <p>The visibility timeout is the deadline for a whole measurement, and since 15 September 2026 a
 * measurement is one reconstruction per 25 m window — eight or nine of them for a segment driven at
 * highway speed, where it used to be one. Setting the timeout to the worst segment's length would
 * also be how long a genuinely stuck worker keeps a segment nobody else can pick up, so the lease is
 * renewed a third of the way through instead: progress extends it, a dead worker does not.
 *
 * <p>Each renewal mints a new pop receipt and invalidates the last, so the delete at the end has to
 * use the one this returns rather than the one the message arrived with.
 *
 * <p>A renewal that fails is logged and abandoned rather than retried. Nothing here can rescue the
 * lease, and the original timeout still stands: the worst case is the redelivery that would have
 * happened without any of this.
 */
export function leaseRenewal({ inbox, message, seconds, log = () => {}, timer = setTimeout }) {
  let receipt = message.popReceipt;
  let handle = null;
  let inflight = null;
  let alive = true;
  const period = Math.max(30_000, Math.floor((seconds * 1000) / 3));

  const schedule = () => {
    handle = timer(() => {
      inflight = tick();
    }, period);
    handle?.unref?.();
  };

  const tick = async () => {
    if (!alive) return;
    try {
      const updated = await inbox.updateMessage(message.messageId, receipt, undefined, seconds);
      if (updated?.popReceipt) receipt = updated.popReceipt;
    } catch (error) {
      log({ event: "lease-renewal-failed", message: message.messageId, error: error.message });
      alive = false;
      return;
    }
    if (alive) schedule();
  };

  schedule();
  return {
    get receipt() {
      return receipt;
    },
    async stop() {
      alive = false;
      if (handle) clearTimeout(handle);
      // A renewal already in flight would mint a receipt after the caller read this one.
      await inflight?.catch(() => {});
    },
  };
}

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

  const poison = async (message, reason, popReceipt = message.popReceipt) => {
    // Deleting without keeping a copy would lose the segment silently, so the copy is written
    // first and the original removed only once it is somewhere else.
    try {
      if (poisonBox) await poisonBox.sendMessage(message.messageText);
      await inbox.deleteMessage(message.messageId, popReceipt);
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
    const lease = leaseRenewal({
      inbox,
      message,
      seconds: azure.visibilityTimeoutSeconds,
      log,
    });
    try {
      await handler(request);
      await lease.stop();
      await inbox.deleteMessage(message.messageId, lease.receipt);
    } catch (error) {
      await lease.stop();
      const attempt = message.dequeueCount ?? 1;
      // A measurement that failed because the worker is going away did not fail on its merits.
      // Container Apps replaces a revision by killing replicas mid-run, and whatever they were
      // measuring surfaces here as an aborted request - which `nextAction` reads as unretryable,
      // because nothing marked it otherwise. Two segments went to the poison queue that way on
      // 16 September 2026, each with two of its windows already measured. While draining, the
      // message is left instead: it costs one visibility timeout and the next worker skips the
      // windows whose packets are already written.
      const action = draining ? nextAction(error, attempt) : "leave";
      log({ event: "message-failed", code: error.code ?? "unknown", attempt, action, error: error.message });
      if (action === "poison") await poison(message, error.code ?? "unknown", lease.receipt);
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
          // Together, not one after another: the batch is only as large as this replica agreed
          // to carry, and each message holds its own lease. Draining them in turn would leave
          // the last one's lease ticking while the first is measured.
          await Promise.all(received.map((message) => (draining ? handleOne(message, handler) : null)));
        }
      })();
      log({ event: "consuming", queue: azure.queue });
    },

    /**
     * Put more work on this worker's own queue.
     *
     * <p>A segment answers by queueing its windows, so the inbox is both where work arrives and
     * where it is split. Writing to the same queue rather than a second one keeps one place to
     * watch, one lease to reason about and one poison queue to look in when something stops.
     */
    async publishWork(request) {
      await inbox.sendMessage(JSON.stringify(request));
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
