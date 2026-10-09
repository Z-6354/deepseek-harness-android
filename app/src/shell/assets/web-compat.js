// Small, origin-scoped document-start compatibility for missing standard APIs only.
(() => {
  'use strict';
  if (typeof Promise.withResolvers !== 'function') {
    Object.defineProperty(Promise, 'withResolvers', {
      configurable: true, writable: true,
      value: {
        withResolvers() {
          const Constructor = this;
          let resolve, reject;
          const promise = new Constructor((resolveFunction, rejectFunction) => {
            if (resolve !== undefined || reject !== undefined) throw new TypeError('Promise executor called twice');
            resolve = resolveFunction; reject = rejectFunction;
          });
          if (typeof resolve !== 'function' || typeof reject !== 'function') throw new TypeError('Invalid promise constructor');
          return { promise, resolve, reject };
        }
      }.withResolvers
    });
  }
  if (typeof AbortSignal !== 'undefined' && typeof AbortController !== 'undefined' && typeof AbortSignal.any !== 'function') {
    if (typeof WeakRef !== 'function' || typeof FinalizationRegistry !== 'function') {
      console.warn('AbortSignal.any compatibility requires WeakRef and FinalizationRegistry; update Android System WebView.');
      return;
    }
    const aborted = Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'aborted').get;
    const reason = Object.getOwnPropertyDescriptor(AbortSignal.prototype, 'reason').get;
    const add = EventTarget.prototype.addEventListener;
    const remove = EventTarget.prototype.removeEventListener;
    const Controller = AbortController;
    // Ephemeron: a live signal retains its controller, but the controller -> signal cycle
    // alone cannot keep a discarded signal alive. Source listeners never capture either.
    const controllers = new WeakMap();
    const finalizer = new FinalizationRegistry(clearListeners);
    function clearListeners(listeners) {
      for (const [source, handler] of listeners) remove.call(source, 'abort', handler);
      listeners.clear();
      finalizer.unregister(listeners);
    }
    // This helper is outside any() so its listener's environment has no per-call controller.
    function handlerFor(source, reference, listeners) {
      return () => {
        const result = reference.deref();
        if (!result) { clearListeners(listeners); return; }
        if (!aborted.call(source) || aborted.call(result)) return;
        const controller = controllers.get(result);
        if (!controller) return;
        clearListeners(listeners);
        controllers.delete(result);
        controller.abort(reason.call(source));
      };
    }
    Object.defineProperty(AbortSignal, 'any', {
      configurable: true, writable: true,
      value: {
        any(signals) {
          if (signals === null || (typeof signals !== 'object' && typeof signals !== 'function')) throw new TypeError('Expected a sequence of AbortSignal');
          const iterate = signals[Symbol.iterator];
          if (typeof iterate !== 'function') throw new TypeError('Expected an iterable of AbortSignal');
          const iterator = iterate.call(signals);
          const sources = [];
          // Native getter performs the brand check, including same-origin cross-realm signals.
          // Complete sequence conversion before checking for an already-aborted input.
          for (const signal of { [Symbol.iterator]() { return iterator; } }) {
            aborted.call(signal);
            sources.push(signal);
          }
          const controller = new Controller();
          for (const signal of sources) {
            if (aborted.call(signal)) { controller.abort(reason.call(signal)); return controller.signal; }
          }
          if (!sources.length) return controller.signal;
          const result = controller.signal;
          const listeners = new Map();
          const reference = new WeakRef(result);
          controllers.set(result, controller);
          finalizer.register(result, listeners, listeners);
          for (const signal of new Set(sources)) {
            const handler = handlerFor(signal, reference, listeners);
            listeners.set(signal, handler);
            // Ignore synthetic abort events; keep listening until the signal really aborts.
            add.call(signal, 'abort', handler);
          }
          return result;
        }
      }.any
    });
  }
})();
