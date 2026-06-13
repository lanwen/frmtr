<#macro setupSection>
          <div class="section-head">
            <h2 id="setup-title">Minimal setup</h2>
            <p>
              The Gradle plugin is intended to make formatter checks part of the normal Java build while keeping
              formatting available as an explicit task.
            </p>
          </div>

          <div class="setup-grid">
            <@codePanel filename="build.gradle.kts" badge="future release">plugins {
    java
    id("dev.lanwen.frmtr") version "&lt;version&gt;"
}

// No frmtr block is required for the default Java source sets.</@codePanel>

            <@coordinatesPanel />
          </div>

          <@taskStrip />
</#macro>
