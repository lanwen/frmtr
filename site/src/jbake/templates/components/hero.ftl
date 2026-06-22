<#macro hero>
        <section class="hero" aria-labelledby="title">
          <div class="hero-paper">
            <h1 id="title">frmtr</h1>
            <p class="hero-summary">
              A fast (allegedly) Java formatter with strong opinions and weak justifications.
              Based on JavaParser.
            </p>
          </div>

          <div class="hero-dark">
            <input class="qs-radio" type="radio" name="quickstart" id="qs-gradle" checked>
            <input class="qs-radio" type="radio" name="quickstart" id="qs-native">

            <p class="qs-word" aria-hidden="true">
              <span class="qs-w qs-w-gradle">GRADLE</span>
              <span class="qs-w qs-w-native">NATIVE</span>
            </p>

            <div class="quickstart">
              <div class="qs-tabs" role="tablist" aria-label="Quick start">
                <label class="qs-tab" for="qs-native">Native</label>
                <label class="qs-tab" for="qs-gradle">Gradle</label>
              </div>

              <div class="qs-right">
                <div class="qs-panel qs-p-gradle">
                  <pre><code><span class="tok-comment">//build.gradle.kts</span>

<span class="tok-key">plugins</span> {
    java
    <span class="tok-fn">id</span>(<span class="tok-str">"dev.lanwen.frmtr"</span>)
}

<span class="tok-dim">$</span> ./gradlew <span class="tok-fn">frmtrCheck</span>
<span class="tok-dim">$</span> ./gradlew <span class="tok-fn">frmtrFormat</span></code></pre>
                </div>

                <div class="qs-panel qs-p-native">
                  <span class="qs-guide" aria-hidden="true">120</span>
                  <pre><code><span class="tok-comment"># frmtr cli</span>

<span class="tok-dim">$</span> frmtr <span class="tok-flag">--check --diff</span> \
       <span class="tok-flag">--render-line-width</span> \
       <span class="tok-flag">--color=always</span> \
       <span class="tok-flag">--progress=always</span> .
<span class="tok-dim">Discovering Java files...</span>
<span class="tok-dim">Processed [240/823, 7 would change].</span>
<span class="tok-dim">(⠋)</span> src/generated/Huge.java
<span class="tok-ok">✓</span> src/Main.java
<span class="tok-bad">✗</span> src/App.java
  <span class="tok-dim">--- origin</span>
  <span class="tok-dim">+++ frmtr</span>
  <span class="tok-del">- int[] dense={1,2};</span>
  <span class="tok-add">+ int[] dense = {1, 2};</span></code></pre>
                </div>
              </div>
            </div>

            <aside class="yellow-panel" aria-label="Formatter default">
              <p class="eyebrow">Default width</p>
              <span class="stamp">120</span>
              <p>No config block is required for ordinary Java source sets.</p>
            </aside>
          </div>
        </section>
</#macro>
