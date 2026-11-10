package com.xinhe.nexus.management.service;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;

/** 业务富文本的统一白名单净化与纯文本摘要转换。 */
final class SafeRichText {
    private static final Set<String> ALLOWED = Set.of(
        "b", "strong", "i", "em", "u", "p", "br", "ul", "ol", "li", "blockquote"
    );
    private static final Pattern TAG = Pattern.compile("(?is)<\\s*(/?)\\s*([a-z0-9]+)[^>]*>");

    private SafeRichText() {}

    static String sanitize(String html) {
        if (html == null || html.isBlank()) return null;
        String safe = html.replaceAll("(?is)<(script|style|iframe|object|svg|math)[^>]*>.*?</\\1\\s*>", "");
        Matcher tags = TAG.matcher(safe);
        StringBuffer out = new StringBuffer();
        while (tags.find()) {
            String tag = tags.group(2).toLowerCase(Locale.ROOT);
            String replacement = ALLOWED.contains(tag) ? "<" + tags.group(1) + tag + ">" : "";
            tags.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        tags.appendTail(out);
        String result = out.toString().trim();
        if (result.length() > 20000) {
            throw new ManagementException(HttpStatus.BAD_REQUEST, "富文本内容过长");
        }
        return result.isBlank() ? null : result;
    }

    static String plainText(String safeHtml) {
        if (safeHtml == null) return null;
        String text = safeHtml
            .replaceAll("(?i)<br\\s*/?>", "\n")
            .replaceAll("(?i)</(p|li|blockquote)>", "\n")
            .replaceAll("<[^>]*>", "")
            .replace("&nbsp;", " ")
            .replaceAll("[\\t\\x0B\\f\\r ]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
        return text.length() <= 1000 ? text : text.substring(0, 1000);
    }
}
