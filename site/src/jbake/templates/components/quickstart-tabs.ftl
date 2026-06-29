<#--
  Reusable, JS-free tab switcher.

  Hidden radios hold the active tab; :has() drives both the sliding indicator
  bar (here) and the panels in the consuming template (keyed off the radio ids).

  The bar position is derived from the checked radio's index via :nth-of-type
  plus a --tab-count custom property, so it stays aligned at any viewport width
  and for any number of tabs — no hardcoded pixel offsets (which is what broke
  the previous version on mobile). Equal-width tabs keep the percentage maths
  exact; see .qs-tabs / .qs-tab in landing.css.

  Params:
    name   - radio group name
    tabs   - sequence of { id, label, checked? } in left-to-right order
    label  - aria-label for the tablist (optional)
-->
<#macro quickstartTabs name tabs label="Quick start">
              <div class="qs-tabs" role="tablist" aria-label="${label}" style="--tab-count: ${tabs?size};">
                <#list tabs as tab>
                <input class="qs-radio" type="radio" name="${name}" id="${tab.id}"<#if tab.checked!false> checked</#if>>
                </#list>
                <#list tabs as tab>
                <label class="qs-tab" for="${tab.id}">${tab.label}</label>
                </#list>
                <span class="qs-tab-bar" aria-hidden="true"></span>
              </div>
</#macro>
