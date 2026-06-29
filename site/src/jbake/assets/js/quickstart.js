/*
 * Gradle quick-start example carousel.
 *
 * The visual cadence is driven entirely by CSS: the active dot's countdown
 * ring drains over a fixed interval and, when it finishes, fires `animationend`
 * which this controller listens for to advance to the next example. Pausing is
 * therefore just `animation-play-state: paused` on the ring (toggled via the
 * `is-paused` / `is-tab-hidden` classes) — no timers to keep in sync.
 *
 * Manual navigation (arrows, dots) restarts the ring so each example gets its
 * full dwell time. Hovering anywhere in the dark band pauses; leaving resumes.
 */
(function () {
  "use strict";

  var root = document.querySelector("[data-ex-carousel]");
  if (!root) return;

  var frames = [].slice.call(root.querySelectorAll(".ex-frame"));
  var dots = [].slice.call(root.querySelectorAll(".ex-dot"));
  var prevBtn = root.querySelector(".ex-prev");
  var nextBtn = root.querySelector(".ex-next");
  var card = root.querySelector(".ex-card");
  var count = frames.length;
  if (!count) return;

  var index = 0;

  function activeProgress() {
    var dot = dots[index];
    return dot ? dot.querySelector(".ring-progress") : null;
  }

  // The frames are absolutely positioned, so the card needs an explicit height.
  // Tracking the active frame keeps a short example from reserving the tallest
  // one's height (which had pushed the hero past one screen); .ex-card eases the
  // change in CSS.
  function syncCardHeight() {
    if (card && frames[index]) card.style.height = frames[index].offsetHeight + "px";
  }

  function render(restartRing) {
    for (var i = 0; i < count; i++) {
      var on = i === index;
      if (frames[i]) frames[i].classList.toggle("is-active", on);
      if (dots[i]) {
        dots[i].classList.toggle("is-active", on);
        dots[i].setAttribute("aria-selected", on ? "true" : "false");
      }
    }
    syncCardHeight();
    if (restartRing) {
      var progress = activeProgress();
      if (progress) {
        // Re-trigger the CSS animation from the start.
        progress.style.animation = "none";
        void progress.getBoundingClientRect();
        progress.style.animation = "";
      }
    }
  }

  function go(target) {
    index = ((target % count) + count) % count;
    render(true);
  }

  // When the active ring finishes draining, move on.
  root.addEventListener("animationend", function (event) {
    if (event.animationName !== "ex-ring-drain") return;
    var dot = event.target.closest(".ex-dot");
    if (dot && dot.classList.contains("is-active")) go(index + 1);
  });

  if (prevBtn) prevBtn.addEventListener("click", function () { go(index - 1); });
  if (nextBtn) nextBtn.addEventListener("click", function () { go(index + 1); });
  dots.forEach(function (dot, i) {
    dot.addEventListener("click", function () { go(i); });
  });

  // Pause while the pointer is anywhere in the dark band; resume on leave.
  var band = root.closest(".hero-dark") || root;
  band.addEventListener("pointerenter", function () { root.classList.add("is-paused"); });
  band.addEventListener("pointerleave", function () { root.classList.remove("is-paused"); });

  // Pause (and reset on return) when the Gradle tab is not the visible one.
  var gradleRadio = document.getElementById("qs-gradle");
  function syncTabVisibility() {
    var visible = !gradleRadio || gradleRadio.checked;
    root.classList.toggle("is-tab-hidden", !visible);
    if (visible) go(index);
  }
  [].slice.call(document.querySelectorAll(".qs-radio")).forEach(function (radio) {
    radio.addEventListener("change", syncTabVisibility);
  });

  // Re-measure when wrapping changes (resize) or the code font finishes loading,
  // since either shifts the active frame's natural height.
  window.addEventListener("resize", syncCardHeight);
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(syncCardHeight);
  }

  render(true);
})();
