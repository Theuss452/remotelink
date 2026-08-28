'use strict';

(() => {
  const touches = new Map();
  let singleTouchTimer = null;
  let multiTouchActive = false;
  let suppressSingleUntilClear = false;
  let lastMultiSentAt = 0;
  let lastTrackpadPinchAt = 0;

  function isStageTarget(target) {
    return target === el.stage || el.stage.contains(target);
  }

  function pointsForMultiTouch() {
    return Array.from(touches.values()).slice(0, 2);
  }

  function sendMulti(type) {
    const points = pointsForMultiTouch();
    if (points.length < 2) return false;
    return sendControl({
      type,
      x1: points[0].x,
      y1: points[0].y,
      x2: points[1].x,
      y2: points[1].y
    });
  }

  function clearSingleTimer() {
    if (singleTouchTimer != null) clearTimeout(singleTouchTimer);
    singleTouchTimer = null;
  }

  function startSingleAfterGrace(pointerId) {
    clearSingleTimer();
    singleTouchTimer = setTimeout(() => {
      singleTouchTimer = null;
      if (multiTouchActive || suppressSingleUntilClear || touches.size !== 1) return;
      const point = touches.get(pointerId);
      if (!point || !controlReady) return;
      pointerStart = {
        id: pointerId,
        x: point.x,
        y: point.y,
        lastX: point.x,
        lastY: point.y,
        lastSent: performance.now(),
        pointerType: 'touch'
      };
      sendControl({type:'drag_start', x:point.x, y:point.y});
    }, 65);
  }

  function beginMultiTouch() {
    clearSingleTimer();
    if (pointerStart) {
      pointerStart = null;
      sendControl({type:'drag_cancel'});
    }
    if (touches.size < 2 || !controlReady) return;
    multiTouchActive = true;
    suppressSingleUntilClear = true;
    lastMultiSentAt = performance.now();
    sendMulti('multitouch_start');
  }

  function finishMultiTouch(cancel = false) {
    if (!multiTouchActive) return;
    if (cancel) sendControl({type:'multitouch_cancel'});
    else sendMulti('multitouch_end');
    multiTouchActive = false;
  }

  document.addEventListener('pointerdown', ev => {
    if (ev.pointerType !== 'touch' || !isStageTarget(ev.target)) return;
    if (!controlReady || !el.stage.classList.contains('streaming')) return;

    enterControlMode();
    const point = normalizedPoint(ev.clientX, ev.clientY);
    if (!point) return;
    touches.set(ev.pointerId, point);
    try { el.stage.setPointerCapture(ev.pointerId); } catch {}

    if (touches.size === 1 && !suppressSingleUntilClear) {
      startSingleAfterGrace(ev.pointerId);
    } else if (touches.size === 2) {
      beginMultiTouch();
    }

    ev.preventDefault();
    ev.stopImmediatePropagation();
  }, {capture:true, passive:false});

  document.addEventListener('pointermove', ev => {
    if (ev.pointerType !== 'touch' || !touches.has(ev.pointerId)) return;
    const previous = touches.get(ev.pointerId);
    const point = normalizedPoint(ev.clientX, ev.clientY) || previous;
    if (point) touches.set(ev.pointerId, point);

    if (multiTouchActive && touches.size >= 2) {
      const now = performance.now();
      if (now - lastMultiSentAt >= 18) {
        lastMultiSentAt = now;
        sendMulti('multitouch_move');
      }
    } else if (pointerStart && pointerStart.id === ev.pointerId && point) {
      const now = performance.now();
      if (now - pointerStart.lastSent >= 18 &&
          Math.hypot(point.x - pointerStart.lastX, point.y - pointerStart.lastY) >= 0.0012) {
        pointerStart.lastX = point.x;
        pointerStart.lastY = point.y;
        pointerStart.lastSent = now;
        sendControl({type:'drag_move', x:point.x, y:point.y});
      }
    }

    ev.preventDefault();
    ev.stopImmediatePropagation();
  }, {capture:true, passive:false});

  document.addEventListener('pointerup', ev => {
    if (ev.pointerType !== 'touch' || !touches.has(ev.pointerId)) return;
    const previous = touches.get(ev.pointerId);
    const point = normalizedPoint(ev.clientX, ev.clientY) || previous;
    if (point) touches.set(ev.pointerId, point);

    if (multiTouchActive) {
      finishMultiTouch(false);
    } else if (pointerStart && pointerStart.id === ev.pointerId) {
      const end = point || {x:pointerStart.lastX, y:pointerStart.lastY};
      pointerStart = null;
      sendControl({type:'drag_end', x:end.x, y:end.y});
    } else if (singleTouchTimer != null && point && !suppressSingleUntilClear) {
      clearSingleTimer();
      sendControl({type:'tap', x:point.x, y:point.y});
    }

    touches.delete(ev.pointerId);
    try { el.stage.releasePointerCapture(ev.pointerId); } catch {}
    if (touches.size === 0) {
      clearSingleTimer();
      suppressSingleUntilClear = false;
      multiTouchActive = false;
    }

    ev.preventDefault();
    ev.stopImmediatePropagation();
  }, {capture:true, passive:false});

  document.addEventListener('pointercancel', ev => {
    if (ev.pointerType !== 'touch' || !touches.has(ev.pointerId)) return;
    clearSingleTimer();
    if (multiTouchActive) finishMultiTouch(true);
    if (pointerStart && pointerStart.id === ev.pointerId) {
      pointerStart = null;
      sendControl({type:'drag_cancel'});
    }
    touches.delete(ev.pointerId);
    suppressSingleUntilClear = touches.size > 0;
    if (touches.size === 0) multiTouchActive = false;
    ev.preventDefault();
    ev.stopImmediatePropagation();
  }, {capture:true, passive:false});

  // Chrome/ChromeOS commonly exposes trackpad pinch as Ctrl+wheel. Convert it to a
  // short native Android two-finger pinch while leaving normal wheel scrolling unchanged.
  document.addEventListener('wheel', ev => {
    if (!ev.ctrlKey || !isStageTarget(ev.target) || !controlMode || !controlReady) return;
    const now = performance.now();
    if (now - lastTrackpadPinchAt < 85) {
      ev.preventDefault();
      ev.stopImmediatePropagation();
      return;
    }
    lastTrackpadPinchAt = now;
    const point = normalizedPoint(ev.clientX, ev.clientY) || {x:0.5, y:0.5};
    const intensity = Math.min(0.55, Math.max(0.18, Math.abs(ev.deltaY) / 500));
    const scale = ev.deltaY < 0 ? 1 + intensity : 1 - intensity;
    sendControl({type:'pinch', x:point.x, y:point.y, scale});
    ev.preventDefault();
    ev.stopImmediatePropagation();
  }, {capture:true, passive:false});

  window.addEventListener('blur', () => {
    clearSingleTimer();
    if (multiTouchActive) finishMultiTouch(true);
    touches.clear();
    suppressSingleUntilClear = false;
    multiTouchActive = false;
  });
})();
