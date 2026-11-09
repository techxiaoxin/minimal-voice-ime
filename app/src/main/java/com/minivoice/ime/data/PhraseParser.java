package com.minivoice.ime.data;

import java.util.ArrayList;
import java.util.List;

/** 把模型吐出来的文字拆成词库条目。纯逻辑，不碰安卓，方便单独验证。 */
public final class PhraseParser {

    private PhraseParser() {
    }

    /** 模型可能加序号、加反引号、换分隔符，这里尽量宽容地读。 */
    public static List<PhraseStore.Phrase> parse(String out, String type) {
        List<PhraseStore.Phrase> list = new ArrayList<>();
        if (out == null || out.isEmpty()) return list;
        for (String line : out.split("\n")) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            t = t.replaceAll("^[-*•]+\\s*", "");
            t = t.replaceAll("^\\d+[.、)]\\s*", "");
            t = t.replace("｜", "|").replace("｜", "|");
            String[] parts = t.split("\\|");
            if (parts.length < 2) {
                // 模型把正文换了行，这行没有分隔符，接到上一条后面，别丢信息
                if (!list.isEmpty()) {
                    PhraseStore.Phrase last = list.get(list.size() - 1);
                    last.text = last.text + "\n" + t;
                }
                continue;
            }
            String code = cleanCode(parts[0]);
            String label = parts[1].trim();
            String text = parts.length >= 3 ? parts[2].trim() : "";
            if (label.isEmpty()) continue;
            if (text.isEmpty()) {
                // 只有缩写和名称的，先存一条占位的，让用户自己补正文
                text = label;
            }
            if (code.isEmpty()) code = codeFromLabel(label);
            PhraseStore.Phrase p = new PhraseStore.Phrase(code, label, text, type);
            list.add(p);
            if (list.size() >= 40) break;
        }
        return list;
    }

    private static String cleanCode(String raw) {
        String c = raw.trim().toLowerCase();
        c = c.replaceAll("[^a-z0-9]", "");
        if (c.length() > 6) c = c.substring(0, 4);
        return c;
    }

    private static String codeFromLabel(String label) {
        StringBuilder sb = new StringBuilder();
        for (char ch : label.toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z')
                    || (ch >= '0' && ch <= '9')) {
                sb.append(Character.toLowerCase(ch));
            }
            if (sb.length() >= 4) break;
        }
        return sb.toString();
    }

    /** 模型完全没干活时的兜底：一行一条，有冒号就前面当名称。 */
    public static List<PhraseStore.Phrase> fallback(String raw, String type) {
        List<PhraseStore.Phrase> list = new ArrayList<>();
        String[] lines = raw.split("\n");
        for (String line : lines) {
            String t = line.trim();
            if (t.isEmpty()) continue;
            String label;
            String text;
            int idx = t.indexOf('：');
            if (idx < 0) idx = t.indexOf(':');
            if (idx > 0 && idx < 20) {
                label = t.substring(0, idx).trim();
                text = t.substring(idx + 1).trim();
            } else {
                label = t.length() > 8 ? t.substring(0, 8) : t;
                text = t;
            }
            if (text.isEmpty()) continue;
            list.add(new PhraseStore.Phrase(codeFromLabel(label), label, text, type));
            if (list.size() >= 20) break;
        }
        return list;
    }
}
