<#macro codePanel filename badge>
            <article class="panel code-panel">
              <div class="panel-head">
                <span>${filename}</span>
                <span>${badge}</span>
              </div>
              <pre><code><#nested></code></pre>
            </article>
</#macro>
