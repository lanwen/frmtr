<#macro architectureSection>
          <section class="architecture" id="architecture" aria-labelledby="architecture-title">
            <div class="section-head">
              <h2 id="architecture-title">Architecture</h2>
              <p>
                The public API owns formatting policy. CLI and Gradle are thin adapters over shared file-oriented
                tooling.
              </p>
            </div>

            <div class="architecture-grid">
              <article class="panel flow" aria-label="Formatting pipeline">
                <div class="flow-row">
                  <div class="flow-box">
                    <strong>Parse</strong>
                    <span>JavaParser reads source with tokens and comments retained.</span>
                  </div>
                  <div class="flow-arrow" aria-hidden="true">-&gt;</div>
                  <div class="flow-box">
                    <strong>Adapt</strong>
                    <span>Formatter-owned syntax views isolate JavaParser APIs from formatting rules.</span>
                  </div>
                </div>
                <div class="flow-row">
                  <div class="flow-box">
                    <strong>Print</strong>
                    <span>Java printers emit a compact document IR instead of assembling strings directly.</span>
                  </div>
                  <div class="flow-arrow" aria-hidden="true">-&gt;</div>
                  <div class="flow-box">
                    <strong>Render</strong>
                    <span>The renderer applies line width, indentation, line endings, and final newline policy.</span>
                  </div>
                </div>
              </article>

              <aside class="panel module-list" aria-label="Modules">
                <h3>Module shape</h3>
                <p><code>frmtr-core</code> formatter API, options, Java pipeline, and document IR.</p>
                <p><code>frmtr-tooling</code> file runs, summaries, diagnostics, and unified diffs.</p>
                <p><code>frmtr-cli</code> command-line adapter and native executable entrypoint.</p>
                <p><code>frmtr-gradle-plugin</code> Gradle tasks and Java source-set integration.</p>
              </aside>
            </div>

            <@linkList />
          </section>
</#macro>
