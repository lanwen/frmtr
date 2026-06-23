<#include "components/site-nav.ftl">
<#include "components/hero.ftl">
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8">
    <meta name="viewport" content="width=device-width, initial-scale=1">
    <meta
      name="description"
      content="frmtr is a fast, opinionated Java formatter with CLI and Gradle plugin adapters.">
    <title>${content.title}</title>
    <script>
      (function () {
        const key = "frmtr-theme";
        let stored = null;
        try {
          stored = localStorage.getItem(key);
        } catch (error) {
          stored = null;
        }
        const wantsDark = window.matchMedia("(prefers-color-scheme: dark)").matches;
        if ((stored && stored === "dark") || (!stored && wantsDark)) {
          document.documentElement.classList.add("dark");
        }
      })();
    </script>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link
      href="https://fonts.googleapis.com/css2?family=Anton&family=Fira+Code:wght@400;500;600;700&family=Unbounded:wght@800;900&family=Work+Sans:wght@500;700;800;900&display=swap"
      rel="stylesheet">
    <link rel="stylesheet" href="${content.rootpath}css/landing.css">
  </head>
  <body>
    <div class="page-shell">
      <@siteNav />

      <main>
        <@hero />
      </main>
    </div>
  </body>
</html>
