package com.querysense.backend.querybuilder;

/** One-edit comparison for the rule engine's typo matching. */
final class Edits {

    static final int MIN_TYPO_LENGTH = 5;

    private Edits() {}

    static boolean withinOne(String a, String b) {
        if (a.equals(b)) {
            return false;
        }
        int la = a.length();
        int lb = b.length();
        if (Math.abs(la - lb) > 1) {
            return false;
        }
        int k = 0;
        while (k < Math.min(la, lb) && a.charAt(k) == b.charAt(k)) {
            k++;
        }
        if (la == lb) {
            if (a.substring(k + 1).equals(b.substring(k + 1))) {
                return true;
            }
            return k + 1 < la && a.charAt(k) == b.charAt(k + 1) && a.charAt(k + 1) == b.charAt(k)
                    && a.substring(k + 2).equals(b.substring(k + 2));
        }
        return la > lb ? a.substring(k + 1).equals(b.substring(k)) : a.substring(k).equals(b.substring(k + 1));
    }
}
