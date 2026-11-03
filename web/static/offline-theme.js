try {
  var theme = localStorage.getItem("stricknani.theme");
  if (theme === "light" || theme === "dark")
    document.documentElement.setAttribute("data-theme", theme);
} catch (error) {}
