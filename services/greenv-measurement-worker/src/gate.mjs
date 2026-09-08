// One measurement at a time, whichever door it came through.
//
// A measurement holds the depth service for its whole run and a CPU core for the whole
// assessment, so two at once is never faster and is often fatal: two assess-grass processes each
// load a segmentation model and materialise a ~110 MB run, and two /infer calls serialise behind
// the depth service's own lock anyway.
//
// The queue path is already bounded by prefetch=1. The HTTP path had no bound at all, and a
// bound on only one of two entrances is not a bound — so it lives here, shared, rather than
// being reimplemented at each door.

export function singleFlight() {
  let active = null;

  return {
    get busy() {
      return active !== null;
    },

    /** Wait for the slot. Used by the queue, where messages are meant to wait their turn. */
    async run(work) {
      while (active) await active.catch(() => {});
      let release;
      active = new Promise((resolve) => { release = resolve; });
      try {
        return await work();
      } finally {
        const done = release;
        active = null;
        done();
      }
    },

    /** Take the slot or say no. Used by HTTP, where a caller would rather be told than queued. */
    tryRun(work) {
      if (active) return null;
      return this.run(work);
    },
  };
}
