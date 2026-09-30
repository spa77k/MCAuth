package com.example.mcauth;

import java.text.Normalizer;

// 認証コードの見せ方と、Discordで入力された文字の読み取りを担当します。
// 統合版はスマホやゲーム機で入力する人が多いため、全角数字や区切りが混じっても受け付けます。
final class CodeFormat {
    private CodeFormat() {
    }

    // Discordの投稿を、桁数チェック前の形にそろえます。
    // 全角数字を半角にし、空白とハイフンを取り除きます。例: "１２３ ４５６" -> "123456"
    static String normalize(String input) {
        String half = Normalizer.normalize(input, Normalizer.Form.NFKC);
        return half.replaceAll("[\\s\\-]", "");
    }

    // キック画面に出すコードを、覚えやすいように区切ります。例: groupSize=3 なら "123456" -> "123 456"
    // 均等に分けられない桁数（4桁や5桁など）は、中途半端な区切りにならないよう、そのまま返します。
    static String display(String code, int groupSize) {
        if (groupSize <= 0 || code.length() <= groupSize || code.length() % groupSize != 0) {
            return code;
        }
        StringBuilder builder = new StringBuilder(code.length() + code.length() / groupSize);
        for (int i = 0; i < code.length(); i += groupSize) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(code, i, i + groupSize);
        }
        return builder.toString();
    }
}
