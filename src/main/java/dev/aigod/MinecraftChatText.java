package dev.aigod;

final class MinecraftChatText {
    private MinecraftChatText() {}

    static String fromModel(String text) {
        return text.strip()
                .replace("**", "")
                .replace("__", "")
                .replace("`", "")
                .replaceAll("(?m)^#{1,6}\\s*", "");
    }
}
