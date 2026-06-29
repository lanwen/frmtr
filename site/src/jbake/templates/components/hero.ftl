<#include "quickstart-tabs.ftl">
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
            <p class="qs-word" aria-hidden="true">
              <span class="qs-w qs-w-gradle">GRADLE</span>
              <span class="qs-w qs-w-native">NATIVE</span>
            </p>

            <div class="quickstart">
              <@quickstartTabs name="quickstart" label="Quick start" tabs=[
                {"id": "qs-native", "label": "Native"},
                {"id": "qs-gradle", "label": "Gradle", "checked": true}
              ] />

              <div class="qs-right">
                <div class="qs-panel qs-p-gradle" data-ex-carousel>
                  <div class="ex-card">
                    <div class="ex-topbar">
                      <span class="win-dots" aria-hidden="true"><i></i><i></i><i></i></span>
                      <span class="ex-title">Single-module</span>
                      <div class="ex-flavor" role="radiogroup" aria-label="Gradle DSL">
                        <input class="flavor-radio" type="radio" name="gradle-flavor" id="flavor-kotlin" checked>
                        <input class="flavor-radio" type="radio" name="gradle-flavor" id="flavor-groovy">
                        <label class="flavor-opt" for="flavor-kotlin">Kotlin</label>
                        <label class="flavor-opt" for="flavor-groovy">Groovy</label>
                      </div>
                    </div>
                    <div class="ex-frames">
                      <div class="ex-frame is-active" role="group" aria-label="Single-module" data-title="Single-module">
                        <pre class="ex-code is-kotlin"><code><span class="cl"><span class="tok-comment">// build.gradle.kts</span></span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-key">plugins</span> {</span>
                          <span class="cl">    java</span>
                          <span class="cl">    <span class="tok-fn">id</span>(<span class="tok-str">"dev.lanwen.frmtr"</span>) <span class="nowrap">version <span class="tok-str">"${config.frmtr_version!"dev"}"</span></span></span>
                          <span class="cl">}</span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-dim">$</span> ./gradlew <span class="tok-task">frmtrCheck</span></span>
                          <span class="cl"><span class="tok-dim">$</span> ./gradlew <span class="tok-task">frmtrFormat</span></span></code></pre>
                        <pre class="ex-code is-groovy"><code><span class="cl"><span class="tok-comment">// build.gradle</span></span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-key">plugins</span> {</span>
                          <span class="cl">    <span class="tok-fn">id</span> <span class="tok-str">'java'</span></span>
                          <span class="cl">    <span class="tok-fn">id</span> <span class="nowrap"><span class="tok-str">'dev.lanwen.frmtr'</span> version <span class="tok-str">'${config.frmtr_version!"dev"}'</span></span></span>
                          <span class="cl">}</span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-dim">$</span> ./gradlew <span class="tok-task">frmtrCheck</span></span>
                          <span class="cl"><span class="tok-dim">$</span> ./gradlew <span class="tok-task">frmtrFormat</span></span></code></pre>
                      </div>

                      <div class="ex-frame" role="group" aria-label="Multi-module" data-title="Multi-module">
                        <pre class="ex-code is-kotlin"><code><span class="cl"><span class="tok-comment">// build.gradle.kts &mdash; root</span></span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-key">plugins</span> {</span>
                          <span class="cl">    <span class="tok-fn">id</span>(<span class="tok-str">"dev.lanwen.frmtr"</span>) <span class="nowrap">version <span class="tok-str">"${config.frmtr_version!"dev"}"</span> apply <span class="tok-key">false</span></span></span>
                          <span class="cl">}</span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-key">subprojects</span> {</span>
                          <span class="cl">    pluginManager.<span class="tok-fn">withPlugin</span>(<span class="tok-str">"java"</span>) {</span>
                          <span class="cl">        pluginManager.<span class="tok-fn">apply</span>(<span class="tok-str">"dev.lanwen.frmtr"</span>)</span>
                          <span class="cl">    }</span>
                          <span class="cl">}</span></code></pre>
                        <pre class="ex-code is-groovy"><code><span class="cl"><span class="tok-comment">// build.gradle &mdash; root</span></span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-key">plugins</span> {</span>
                          <span class="cl">    <span class="tok-fn">id</span> <span class="nowrap"><span class="tok-str">'dev.lanwen.frmtr'</span> version <span class="tok-str">'${config.frmtr_version!"dev"}'</span> apply <span class="tok-key">false</span></span></span>
                          <span class="cl">}</span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-key">subprojects</span> {</span>
                          <span class="cl">    pluginManager.<span class="tok-fn">withPlugin</span>(<span class="tok-str">'java'</span>) {</span>
                          <span class="cl">        pluginManager.<span class="tok-fn">apply</span>(<span class="tok-str">'dev.lanwen.frmtr'</span>)</span>
                          <span class="cl">    }</span>
                          <span class="cl">}</span></code></pre>
                      </div>

                      <div class="ex-frame" role="group" aria-label="Multi-module with submodule override" data-title="Multi-module &mdash; submodule override">
                        <pre class="ex-code is-kotlin"><code><span class="cl"><span class="tok-comment">// build.gradle.kts &mdash; root</span></span>
                          <span class="cl">frmtr {</span>
                          <span class="cl">    java { <span class="tok-fn">exclude</span>(<span class="tok-str">"**/generated/**"</span>) }</span>
                          <span class="cl">}</span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-comment">// service/build.gradle.kts &mdash; overrides root</span></span>
                          <span class="cl">frmtr {</span>
                          <span class="cl">    java { <span class="tok-fn">exclude</span>(<span class="tok-str">"**/legacy/**"</span>) }</span>
                          <span class="cl">}</span></code></pre>
                        <pre class="ex-code is-groovy"><code><span class="cl"><span class="tok-comment">// build.gradle &mdash; root</span></span>
                          <span class="cl">frmtr {</span>
                          <span class="cl">    java { <span class="tok-fn">exclude</span> <span class="tok-str">'**/generated/**'</span> }</span>
                          <span class="cl">}</span>
                          <span class="cl">&nbsp;</span>
                          <span class="cl"><span class="tok-comment">// service/build.gradle &mdash; overrides root</span></span>
                          <span class="cl">frmtr {</span>
                          <span class="cl">    java { <span class="tok-fn">exclude</span> <span class="tok-str">'**/legacy/**'</span> }</span>
                          <span class="cl">}</span></code></pre>
                      </div>
                    </div>
                  </div>

                  <div class="ex-nav" aria-label="Gradle examples">
                    <button class="ex-arrow ex-prev" type="button" aria-label="Previous example"><span class="chev" aria-hidden="true"></span></button>
                    <div class="ex-dots" role="tablist" aria-label="Choose Gradle example">
                      <button class="ex-dot is-active" type="button" role="tab" aria-selected="true" aria-label="Single-module">
                        <svg class="ring" viewBox="0 0 20 20" aria-hidden="true"><circle class="ring-progress" cx="10" cy="10" r="5"></circle></svg>
                      </button>
                      <button class="ex-dot" type="button" role="tab" aria-selected="false" aria-label="Multi-module">
                        <svg class="ring" viewBox="0 0 20 20" aria-hidden="true"><circle class="ring-progress" cx="10" cy="10" r="5"></circle></svg>
                      </button>
                      <button class="ex-dot" type="button" role="tab" aria-selected="false" aria-label="Multi-module with submodule override">
                        <svg class="ring" viewBox="0 0 20 20" aria-hidden="true"><circle class="ring-progress" cx="10" cy="10" r="5"></circle></svg>
                      </button>
                    </div>
                    <button class="ex-arrow ex-next" type="button" aria-label="Next example"><span class="chev" aria-hidden="true"></span></button>
                  </div>
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
