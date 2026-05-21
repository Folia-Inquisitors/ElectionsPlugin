package com.electionsplugin.policy;

import java.util.ArrayList;
import java.util.List;

public final class DiffUtil {
    private DiffUtil() {
    }

    public static String unifiedDiff(String oldText, String newText, int contextLines) {
        List<String> oldLines = oldText.lines().toList();
        List<String> newLines = newText.lines().toList();
        int prefix = 0;
        while (prefix < oldLines.size() && prefix < newLines.size() && oldLines.get(prefix).equals(newLines.get(prefix))) {
            prefix++;
        }

        int oldSuffix = oldLines.size() - 1;
        int newSuffix = newLines.size() - 1;
        while (oldSuffix >= prefix && newSuffix >= prefix && oldLines.get(oldSuffix).equals(newLines.get(newSuffix))) {
            oldSuffix--;
            newSuffix--;
        }

        int start = Math.max(0, prefix - contextLines);
        int oldEnd = Math.min(oldLines.size() - 1, oldSuffix + contextLines);
        int newEnd = Math.min(newLines.size() - 1, newSuffix + contextLines);

        List<String> output = new ArrayList<>();
        output.add("--- current");
        output.add("+++ proposed");
        output.add("@@");

        for (int i = start; i < prefix && i < oldLines.size(); i++) {
            output.add(" " + oldLines.get(i));
        }
        for (int i = prefix; i <= oldSuffix && i < oldLines.size(); i++) {
            output.add("-" + oldLines.get(i));
        }
        for (int i = prefix; i <= newSuffix && i < newLines.size(); i++) {
            output.add("+" + newLines.get(i));
        }
        int suffixStart = Math.max(prefix, Math.min(oldSuffix + 1, newSuffix + 1));
        int suffixEnd = Math.max(oldEnd, newEnd);
        for (int i = suffixStart; i <= suffixEnd && i < oldLines.size(); i++) {
            output.add(" " + oldLines.get(i));
        }

        if (output.size() == 3) {
            output.add(" No text changes detected.");
        }
        return String.join("\n", output);
    }
}
