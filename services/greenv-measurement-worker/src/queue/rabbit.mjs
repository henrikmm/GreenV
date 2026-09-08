// The automatic trigger.
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

export async function connectQueue(config, { log = () => {} } = {}) {
  const url = `amqp://${encodeURIComponent(config.user)}:${encodeURIComponent(config.password)}@${config.host}:${config.port}`;
  const connection = await amqplib.connect(url);
  const channel = await connection.createChannel();
  await channel.prefetch(config.prefetch);

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
          // Requeue only what a retry could fix. A malformed request or a mock service will fail
          // identically forever, and requeuing it starves everything behind it.
          const retry = error.retryable === true;
          log({ event: "message-failed", code: error.code ?? "unknown", retry, error: error.message });
          channel.nack(message, false, retry);
        }
      });
      log({ event: "consuming", queue: config.queue });
    },

    /** Announce a finished measurement so the API can move the segment on. */
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
