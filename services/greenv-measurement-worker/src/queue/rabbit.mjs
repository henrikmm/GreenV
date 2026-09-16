// The automatic trigger, over RabbitMQ. `azure.mjs` is the same trigger over Azure Queue Storage.
//
// One message per segment, because one segment is one depth run: the extractor samples 10 fps
// over at most 112 frames, which is about 100 JPEGs for a ten-second segment, and an L4 runs out
// above 144. Two segments cannot be merged into one inference, so there is no batch to assemble
// here — the batching that matters is temporal, and it happens by itself when a drive's segments
// arrive close enough together to ride one warm instance.
//
// Prefetch is one. A second message in flight would sit in a buffer while the first holds both
// the GPU and the CPU, and would time out there rather than anywhere useful.

import amqplib from "amqplib";

/**
 * How a retryable failure is retried.
 *
 * Never by requeueing in place. `nack(requeue=true)` returns the message to the head of the
 * queue, RabbitMQ redelivers it immediately, and a fault that persists — the depth service being
 * unreachable, which is the compose stack's own default — becomes a hot loop against the broker
 * at full speed. The frame extractor bounds the same thing with `attempt + 1 < maxAttempts`, so
 * this republishes with an incremented attempt and a growing delay, and drops the message once
 * the attempts are spent.
 */
const MAX_ATTEMPTS = Number(process.env.GREENV_MEASUREMENT_MAX_ATTEMPTS ?? 3);
const RETRY_DELAY_MS = Number(process.env.GREENV_MEASUREMENT_RETRY_DELAY_MS ?? 15_000);

export async function connectRabbitQueue(config, { log = () => {}, onLost } = {}) {
  const url = `amqp://${encodeURIComponent(config.user)}:${encodeURIComponent(config.password)}@${config.host}:${config.port}`;
  const connection = await amqplib.connect(url);
  const channel = await connection.createChannel();
  await channel.prefetch(config.prefetch);

  // Without these a broker restart stops delivery while the process stays alive and healthy —
  // segments queue up unmeasured with nothing reporting that anything is wrong. Exiting lets the
  // supervisor restart and reconnect, which is the recovery that actually works.
  const fatal = (what) => (error) => {
    log({ event: "queue-lost", what, error: error?.message ?? String(error ?? "closed") });
    process.exitCode = 1;
    onLost?.();
  };
  connection.on("error", fatal("connection"));
  connection.on("close", fatal("connection"));
  channel.on("error", fatal("channel"));
  channel.on("close", fatal("channel"));

  if (config.dynamic) {
    // Declaring topology from a worker is convenient locally and wrong in a deployment, where
    // queues are infrastructure with their own lifecycle. Same switch the Java services use.
    // Direct, durable, not topic: `RabbitMqSegmentQueueConfiguration` declares `greenv.capture`
    // as a DirectExchange, and asserting a different type against an existing exchange closes
    // the channel with PRECONDITION_FAILED rather than failing anywhere legible.
    await channel.assertExchange(config.exchange, "direct", { durable: true });
    await channel.assertQueue(config.queue, { durable: true });
    await channel.bindQueue(config.queue, config.exchange, config.routingKey);
  }

  return {
    async consume(handler) {
      await channel.consume(config.queue, async (message) => {
        if (!message) return;
        let request;
        try {
          request = JSON.parse(message.content.toString("utf8"));
        } catch (error) {
          // Unparseable content will never parse on a retry. Dropping it is the only way not to
          // block the queue behind it forever.
          log({ event: "message-rejected", reason: "unparseable", error: error.message });
          channel.nack(message, false, false);
          return;
        }
        try {
          await handler(request);
          channel.ack(message);
        } catch (error) {
          // Retry only what a retry could fix. A malformed request or a mock service will fail
          // identically forever, so it is dropped rather than left to starve everything behind it.
          const attempt = Number(message.properties?.headers?.["x-measurement-attempt"] ?? 0);
          const retry = error.retryable === true && attempt + 1 < MAX_ATTEMPTS;
          log({ event: "message-failed", code: error.code ?? "unknown", attempt, retry, error: error.message });

          if (retry) {
            // Republish rather than requeue, so the attempt count survives and the delay is real.
            // Acked only after the replacement is on the exchange: a crash in between redelivers
            // the original, which costs one duplicate attempt and never a lost segment.
            const delay = RETRY_DELAY_MS * (attempt + 1);
            setTimeout(() => {
              try {
                channel.publish(config.exchange, config.routingKey, message.content, {
                  contentType: "application/json",
                  persistent: true,
                  headers: { ...message.properties?.headers, "x-measurement-attempt": attempt + 1 },
                });
                channel.ack(message);
              } catch (republishError) {
                log({ event: "retry-publish-failed", error: republishError.message });
                channel.nack(message, false, false);
              }
            }, delay).unref();
            return;
          }
          channel.nack(message, false, false);
        }
      });
      log({ event: "consuming", queue: config.queue });
    },

    /** Announce a finished measurement so the API can move the segment on. */
    /** Put more work on this worker's own queue — a segment queueing its windows. */
    async publishWork(request) {
      channel.publish(
          config.exchange,
          config.routingKey,
          Buffer.from(JSON.stringify(request)),
          { contentType: "application/json", persistent: true });
    },

    async publishResult(result) {
      channel.publish(
        config.exchange,
        config.resultRoutingKey,
        Buffer.from(JSON.stringify(result)),
        { contentType: "application/json", persistent: true },
      );
    },

    async close() {
      await channel.close().catch(() => {});
      await connection.close().catch(() => {});
    },
  };
}
