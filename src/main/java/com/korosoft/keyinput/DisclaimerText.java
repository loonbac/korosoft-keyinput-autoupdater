package com.korosoft.keyinput;

import java.util.List;

/**
 * Single source of truth for every player-facing string in the custom boot flow (the two
 * disclaimer pages, the branded loading screen and the launcher menu). Kept apart from the code
 * that draws it so the copy can be edited without touching rendering logic. All text is Spanish
 * (neutral, "tú"), matching the rest of the KoroSoft client.
 *
 * <p>The wording is not filler: each attribution line corresponds to something actually shipped by
 * this project (third-party SFX, AI-assisted item art, world-gen datapacks, third-party plugins,
 * the key-capturing client mod, the mandatory resource pack), so the notice is accurate.
 */
public final class DisclaimerText {

    private DisclaimerText() {
    }

    // --- Disclaimer, page 1 of 2 (black screen, white text, shown first at launch) ---

    public static final String P1_TITLE = "KOROSOFT SMP";

    public static final List<String> P1_BODY = List.of(
            "Modpack no oficial creado por la comunidad de KoroSoft.",
            "No está afiliado, patrocinado ni asociado con Mojang Studios ni con Microsoft. "
                    + "\"Minecraft\" es una marca registrada de Mojang Synergies AB.",
            "Este modpack utiliza texturas, sonidos y modelos que NO fueron creados por el equipo "
                    + "KoroSoft. No nos atribuimos su autoría; todos los derechos pertenecen a sus "
                    + "respectivos dueños."
    );

    // --- Disclaimer, page 2 of 2 ---

    public static final String P2_TITLE = "ANTES DE JUGAR";

    public static final List<String> P2_BODY = List.of(
            "Algunos efectos de sonido provienen de obras de terceros (por ejemplo, ULTRAKILL, "
                    + "© New Blood Interactive) y se usan únicamente con fines ambientales.",
            "Parte del arte de ítems fue generado con herramientas de IA y reprocesado a mano.",
            "La generación del mundo utiliza datapacks de terceros (Terralith, Tectonic, William "
                    + "Wythers' Overhauled Overworld, Dungeons and Taverns, entre otros).",
            "El servidor funciona sobre software de terceros (Purpur, Velocity, Nexo, MythicMobs, "
                    + "MMOItems, DualWield y otros); ninguno es creación de KoroSoft.",
            "Al conectarte se descargará un resource pack obligatorio y el mod cliente registrará "
                    + "pulsaciones de teclas durante la partida para sus mecánicas propias. No se "
                    + "recopilan datos personales ni telemetría fuera del juego.",
            "Al continuar, aceptas jugar bajo estas condiciones."
    );

    /** Bottom-of-page hint, shown on both disclaimer pages. */
    public static final String DISCLAIMER_HINT = "Haz clic para continuar";

    // --- Branded loading screen (dark background, KoroSoft title, progress bar) ---

    public static final String LOADING_TITLE = "KOROSOFT SMP";
    public static final String LOADING_SUBTITLE = "Cargando recursos…";

    // --- Launcher menu ---

    public static final String MENU_START = "INICIAR JUEGO";
    public static final String MENU_HINT = "Haz clic para comenzar";
    public static final String MENU_QUIT = "Salir";
    public static final String MENU_OPTIONS = "Opciones";
    public static final String MENU_LOGOUT = "Cerrar sesión";

    // --- Connect overlay (shown from the menu click until the world is ready) ---

    public static final String CONNECT_CONNECTING = "Conectando con el servidor...";
    public static final String CONNECT_DOWNLOADING = "Descargando recursos...";
    public static final String CONNECT_RELOADING = "Cargando texturas...";
    public static final String CONNECT_ENTERING = "Entrando al mundo...";
}
