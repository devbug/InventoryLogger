package com.pocky.invbackups.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.pocky.invbackups.InventoryBackupsMod;
import net.minecraft.server.level.ServerPlayer;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Server-side translation helper for translating text without client-side mod installation
 * Loads language files from server resources and provides translations
 */
public class TranslationHelper {
    
    private static final Map<String, Map<String, String>> TRANSLATIONS = new HashMap<>();
    private static final Gson GSON = new Gson();
    private static final String DEFAULT_LANG = "en_us";
    
    static {
        // Load all available language files
        loadLanguage("en_us");
        loadLanguage("ko_kr");
        loadLanguage("ru_ru");
    }
    
    /**
     * Load language file from resources
     */
    private static void loadLanguage(String langCode) {
        try {
            String path = "/assets/inventorybackups/lang/" + langCode + ".json";
            InputStream inputStream = TranslationHelper.class.getResourceAsStream(path);
            
            if (inputStream != null) {
                InputStreamReader reader = new InputStreamReader(inputStream, StandardCharsets.UTF_8);
                JsonObject json = GSON.fromJson(reader, JsonObject.class);
                
                Map<String, String> translations = new HashMap<>();
                json.entrySet().forEach(entry -> {
                    translations.put(entry.getKey(), entry.getValue().getAsString());
                });
                
                TRANSLATIONS.put(langCode, translations);
                InventoryBackupsMod.LOGGER.info("Loaded {} translations for language: {}", translations.size(), langCode);
                reader.close();
            } else {
                InventoryBackupsMod.LOGGER.warn("Language file not found: {}", path);
            }
        } catch (Exception e) {
            InventoryBackupsMod.LOGGER.error("Failed to load language file: {}", langCode, e);
        }
    }
    
    /**
     * Get player's language preference
     * Note: ServerPlayer.getLanguage() returns the client's language setting
     */
    public static String getPlayerLanguage(ServerPlayer player) {
        try {
            // Try to get client language - this is sent from client to server
            String clientLang = player.getLanguage();
            if (clientLang != null && !clientLang.isEmpty()) {
                // Normalize language code (e.g., "ko_KR" -> "ko_kr")
                clientLang = clientLang.toLowerCase().replace("-", "_");
                if (TRANSLATIONS.containsKey(clientLang)) {
                    return clientLang;
                }
            }
        } catch (Exception e) {
            // Fallback to default
        }
        return DEFAULT_LANG;
    }
    
    /**
     * Translate a key for a specific player (uses player's language)
     */
    public static String translate(ServerPlayer player, String key, Object... args) {
        String lang = getPlayerLanguage(player);
        return translate(lang, key, args);
    }
    
    /**
     * Translate a key for a specific language
     */
    public static String translate(String lang, String key, Object... args) {
        Map<String, String> langMap = TRANSLATIONS.get(lang);
        
        // Fallback to default language if not found
        if (langMap == null) {
            langMap = TRANSLATIONS.get(DEFAULT_LANG);
        }
        
        // Get translation or return key if not found
        String translation = langMap != null ? langMap.getOrDefault(key, key) : key;
        
        // Format with arguments if provided
        if (args.length > 0) {
            try {
                translation = String.format(translation, args);
            } catch (Exception e) {
                InventoryBackupsMod.LOGGER.warn("Failed to format translation: {} with args", key, e);
            }
        }
        
        return translation;
    }
    
    /**
     * Check if a translation exists
     */
    public static boolean hasTranslation(String lang, String key) {
        Map<String, String> langMap = TRANSLATIONS.get(lang);
        return langMap != null && langMap.containsKey(key);
    }
}
