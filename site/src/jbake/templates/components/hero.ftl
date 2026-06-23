<#macro hero>
        <section class="hero" aria-labelledby="title">
          <div class="hero-paper">
            <div class="hero-coords">
              <p class="coords">dev.lanwen.frmtr:<span class="coords-rotator"><span class="coords-rotor"><span>frmtr-core:${config.frmtr_version!"dev"}</span><span>frmtr-tooling:${config.frmtr_version!"dev"}</span><span>frmtr-gradle-plugin:${config.frmtr_version!"dev"}</span><span>frmtr-core:${config.frmtr_version!"dev"}</span></span></span></p>
              <a class="command" href="#start">Quick start <span class="command-arrow" aria-hidden="true">&#8595;</span></a>
            </div>

            <div class="hero-headline">
              <h1 id="title" class="t-shimmer" data-text="frmtr">frmtr</h1>
              <p class="hero-summary">
                A fast (allegedly) Java formatter with strong opinions and weak justifications.
                Based on <a href="https://javaparser.org" target="_blank" rel="noopener">JavaParser</a>.
              </p>
            </div>
          </div>

          <div class="hero-dark" id="start">
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
                <span class="qs-tab-bar" aria-hidden="true"></span>
              </div>

              <div class="qs-right">
                <div class="qs-panel qs-p-gradle">
                  <pre><code><span class="cl"><span class="tok-comment">//build.gradle.kts</span></span>
                    <span class="cl">&nbsp;</span>
                    <span class="cl"><span class="tok-key">plugins</span> {</span>
                    <span class="cl">    java</span>
                    <span class="cl">    <span class="tok-fn">id</span>(<span class="tok-str">"dev.lanwen.frmtr"</span>) <span class="nowrap">version <span class="tok-str">"${config.frmtr_version!"dev"}"</span></span></span>
                    <span class="cl">}</span>
                    <span class="cl">&nbsp;</span>
                    <span class="cl"><span class="tok-dim">$</span> ./gradlew <span class="tok-task">frmtrCheck</span></span>
                    <span class="cl"><span class="tok-dim">$</span> ./gradlew <span class="tok-task">frmtrFormat</span></span></code></pre>
                </div>

                <div class="qs-panel qs-p-native">
                  <pre><code><span class="tok-comment"># frmtr cli</span>

<span class="tok-dim">$</span> <span class="tok-task">frmtr</span> <span class="tok-flag">--check --diff --render-line-width</span> \
       <span class="tok-flag">--color=always --progress=always</span> .
<span class="tok-dim">Discovering Java files...</span>
<span class="tok-dim">Processed [240/823, 7 would change].</span>
<span class="tok-dim">(⠋)</span> src/generated/Huge.java
<span class="tok-ok">✓</span> src/Main.java
<span class="tok-bad">✗</span> src/App.java
  <span class="tok-dim">--- origin</span>               <span class="tok-dim">⋮ 120</span>
  <span class="tok-dim">+++ frmtr</span>                <span class="tok-dim">⋮</span>
  <span class="tok-del">- int[] dense={1,2};</span>     <span class="tok-dim">⋮</span>
  <span class="tok-add">+ int[] dense = {1, 2};</span>  <span class="tok-dim">⋮</span></code></pre>
                </div>
              </div>
            </div>

            <aside class="yellow-panel" aria-label="Zero configuration">
              <div class="stamp-stack">
                <span class="stamp">ZERO</span>
                <span class="stamp-rule" aria-hidden="true"></span>
                <span class="stamp-sub">configuration<br>sensible defaults</span>
              </div>
              <p>Just plug and play &mdash; readable Java without the hassle.</p>
            </aside>
          </div>
        </section>
</#macro>
