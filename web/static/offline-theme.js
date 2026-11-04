try {
  var lang = localStorage.getItem("stricknani.locale");
  if (lang !== "de" && lang !== "en")
    lang =
      String(navigator.language || "en").slice(0, 2) === "de" ? "de" : "en";
  document.documentElement.setAttribute("data-lang", lang);
  document.documentElement.lang = lang;
} catch (error) {}
try {
  var theme = localStorage.getItem("stricknani.theme");
  if (theme === "light" || theme === "dark")
    document.documentElement.setAttribute("data-theme", theme);
} catch (error) {}
