// Comparison filters run in the browser: the page already has all rows, so filtering is instant.
(function () {
    "use strict";

    function applyFilters() {
        const table = document.getElementById("apps");
        if (!table) return;
        const types = new Set(Array.from(document.querySelectorAll("#filters input[name=type]:checked"), (input) => input.value));
        const showUnchanged = document.getElementById("show-unchanged")?.checked ?? false;
        const kind = document.getElementById("app-kind")?.value ?? "all";
        const query = (document.getElementById("search")?.value ?? "").trim().toLowerCase();

        let shown = 0;
        let total = 0;
        table.querySelectorAll("tbody tr[data-change]").forEach((row) => {
            total++;
            const change = row.dataset.change;
            const system = row.dataset.system === "true";
            const visible = (change === "UNCHANGED" ? showUnchanged : types.has(change))
                && (kind === "all" || (kind === "system") === system)
                && (query === "" || row.dataset.search.includes(query));
            row.hidden = !visible;
            if (visible) shown++;
        });

        const noMatch = document.getElementById("no-match");
        if (noMatch) noMatch.hidden = shown > 0;
        const counter = document.getElementById("shown-count");
        if (counter) counter.textContent = `Showing ${shown} of ${total} apps`;
    }

    function initToasts(root) {
        root.querySelectorAll?.(".toast:not([data-timer])").forEach((toast) => {
            toast.dataset.timer = "1";
            toast.addEventListener("click", () => toast.remove());
            const delay = toast.classList.contains("error") ? 15000 : 8000;
            setTimeout(() => toast.remove(), delay);
        });
    }

    document.addEventListener("DOMContentLoaded", () => {
        applyFilters();
        const filters = document.getElementById("filters");
        filters?.addEventListener("input", applyFilters);
        filters?.addEventListener("change", applyFilters);
        initToasts(document);
    });

    // Rows replaced after an action must keep following the current filters.
    document.addEventListener("htmx:afterSettle", () => {
        applyFilters();
        initToasts(document);
    });

    // The desktop launcher opens the app window at "/?window=app". sessionStorage keeps that for every page
    // shown in this window; a normal browser tab never has it.
    const appWindow = (() => {
        try {
            if (new URLSearchParams(location.search).get("window") === "app") sessionStorage.setItem("appWindow", "1");
            return sessionStorage.getItem("appWindow") === "1";
        } catch (e) {
            return false;
        }
    })();

    // The app window has no tabs or address bar: links to other sites open in the user's normal browser.
    document.addEventListener("click", (event) => {
        if (!appWindow || event.defaultPrevented || event.button !== 0) return;
        const link = event.target.closest?.("a[href]");
        if (!link || link.protocol !== "https:" || link.origin === location.origin) return;
        event.preventDefault();
        fetch("/open-link", {method: "POST", headers: {"HX-Request": "true"}, body: new URLSearchParams({url: link.href})})
            .then((response) => {
                if (!response.ok) throw new Error(`HTTP ${response.status}`);
            })
            .catch(() => window.open(link.href, "_blank", "noopener"));
    });
})();
