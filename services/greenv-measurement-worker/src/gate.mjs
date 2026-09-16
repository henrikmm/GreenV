// How many measurements this process runs at once, whichever door they came through.
//
// One at a time until 16 September 2026, and the reason then was real: a measurement held the
// depth service for its whole run, and two assess-grass processes each loaded a segmentation
// model and materialised a ~110 MB run on a container with one core.
//
// Both halves of that changed. The depth service serves several windows at once, and the
// measurement worker runs on four vCPU and eight GiB — where three windows in flight measured
// 2.45 cores and 2.1 GiB, and the wall clock of a window is mostly spent waiting for the depth
// handler to build and upload the 83 MB it keeps rather than computing here. Serialising that
// wait is what made a nine-window segment take over an hour.
//
// So the bound is a number rather than a boolean, and it is still a bound: the HTTP door and the
// queue door share it, because a limit on one entrance is not a limit.

export function boundedFlight(limit = 1) {
  const ceiling = Math.max(1, Math.floor(limit));
  let active = 0;
  const waiting = [];

  const release = () => {
    active -= 1;
    const next = waiting.shift();
    if (next) next();
  };

  return {
    get busy() {
      return active >= ceiling;
    },

    get inFlight() {
      return active;
    },

    /**
     * Wait for a slot. Used by the queue, where messages are meant to wait their turn.
     *
     * <p>A free slot is taken without yielding first: an `await` before `work()` would hand the
     * caller back a promise for something that has not started, and a caller that takes a slot
     * and then waits a turn is a slot nobody is using.
     */
    async run(work) {
      if (active < ceiling) {
        active += 1;
      } else {
        await new Promise((resolve) => waiting.push(resolve));
        active += 1;
      }
      try {
        return await work();
      } finally {
        release();
      }
    },

    /** Take a slot or say no. Used by HTTP, where a caller would rather be told than queued. */
    tryRun(work) {
      if (this.busy) return null;
      return this.run(work);
    },
  };
}

/** The old name, for the callers that want exactly one. */
export function singleFlight() {
  return boundedFlight(1);
}
