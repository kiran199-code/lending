package com.bank.lending.iam.web;

/** The few pages of the sign-in flow. Plain HTML, no scripts, no external resources; every dynamic value goes through {@link #esc}. */
public final class Html {
    private Html() {}

    public static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> b.append("&amp;");
                case '<' -> b.append("&lt;");
                case '>' -> b.append("&gt;");
                case '"' -> b.append("&quot;");
                case '\'' -> b.append("&#39;");
                default -> b.append(c);
            }
        }
        return b.toString();
    }

    public static String page(String title, String body) {
        return "<!DOCTYPE html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<title>" + esc(title) + "</title><style>"
                + "body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;background:#f4f5f7;margin:0;color:#1d2433}"
                + "main{max-width:26rem;margin:8vh auto;background:#fff;border-radius:10px;padding:2rem;box-shadow:0 1px 6px rgba(0,0,0,.12)}"
                + "h1{font-size:1.25rem;margin:0 0 1rem}label{display:block;margin:.9rem 0 .25rem;font-size:.875rem}"
                + "input[type=text],input[type=password]{width:100%;box-sizing:border-box;padding:.6rem;border:1px solid #b9c0cc;border-radius:6px;font-size:1rem}"
                + "button{margin-top:1.2rem;width:100%;padding:.7rem;border:0;border-radius:6px;background:#1f4fd8;color:#fff;font-size:1rem;cursor:pointer}"
                + ".msg{padding:.6rem .8rem;border-radius:6px;margin-bottom:1rem;font-size:.9rem}.err{background:#fde8e8;color:#8a1c1c}.ok{background:#e6f4ea;color:#17602b}"
                + ".hint{font-size:.8rem;color:#5c667a}code{display:block;word-break:break-all;background:#f0f2f6;padding:.5rem;border-radius:6px;margin:.4rem 0}"
                + "ul{padding-left:1.2rem}</style></head><body><main>" + body + "</main></body></html>";
    }

    public static String message(String kind, String text) {
        return "<div class=\"msg " + esc(kind) + "\" role=\"alert\">" + esc(text) + "</div>";
    }

    public static String list(java.util.List<String> items) {
        StringBuilder b = new StringBuilder("<ul>");
        for (String i : items) b.append("<li>").append(esc(i)).append("</li>");
        return b.append("</ul>").toString();
    }

    public static String csrf(String parameterName, String token) {
        return "<input type=\"hidden\" name=\"" + esc(parameterName) + "\" value=\"" + esc(token) + "\">";
    }

    public static String field(String label, String name, String type, String value, String autocomplete) {
        return "<label for=\"" + esc(name) + "\">" + esc(label) + "</label><input id=\"" + esc(name) + "\" name=\"" + esc(name) + "\" type=\"" + esc(type)
                + "\" autocomplete=\"" + esc(autocomplete) + "\" value=\"" + esc(value) + "\" required>";
    }
}
